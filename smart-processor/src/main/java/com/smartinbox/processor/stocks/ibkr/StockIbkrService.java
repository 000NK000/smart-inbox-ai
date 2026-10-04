package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.stocks.StockSecretStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local account/market-data adapter. It has no order, alert, or broker watchlist mutation path. */
@Service
public class StockIbkrService {
    static final String CACHE_KEY = "ibkr-portfolio";
    static final String NAV_CACHE_KEY = "ibkr-nav-samples";
    private final ObjectMapper mapper;
    private final StockSecretStore store;
    private final IbkrAuthorization authorization;
    private final IbkrMcp mcp;
    private final Clock clock;
    private final Map<String, CachedHistory> historyCache = new LinkedHashMap<>();
    private JsonNode performance;
    private Instant performanceFetchedAt;

    @Autowired
    public StockIbkrService(ObjectMapper mapper, StockSecretStore store, @Value("${server.port:8083}") int port) {
        this(mapper, store, new IbkrHttp.Live(), Clock.systemUTC(),
                "http://127.0.0.1:" + port + "/api/stocks/auth/ibkr/callback");
    }

    StockIbkrService(ObjectMapper mapper, StockSecretStore store, IbkrHttp http, Clock clock, String callback) {
        this.mapper = mapper;
        this.store = store;
        this.clock = clock;
        authorization = new IbkrAuthorization(mapper, store, http, clock, callback);
        mcp = new IbkrMcp(mapper, http, authorization);
    }

    public synchronized Map<String, Object> status() {
        boolean connected = authorization.connected();
        return Map.of("connected", connected, "loginSupported", true, "message", connected
                ? "IBKR 已授权只读访问。刷新后显示最新可用数据。"
                : "连接 IBKR 以读取持仓和行情；请在 IBKR 官方登录页完成授权。");
    }

    public synchronized Map<String, Object> beginAuthorization() {
        try { return Map.of("url", authorization.begin()); }
        catch (IllegalStateException failure) { throw safeFailure(failure); }
    }

    public synchronized void finishAuthorization(Map<String, String> values) {
        try { authorization.finish(values); }
        catch (IllegalStateException failure) { throw safeFailure(failure); }
        mcp.reset();
        clearHistory();
        store.delete(CACHE_KEY); // A new consent may select a different account.
        store.delete(NAV_CACHE_KEY);
    }

    public synchronized void disconnect() {
        authorization.disconnect();
        mcp.reset();
        clearHistory();
        store.delete(CACHE_KEY);
        store.delete(NAV_CACHE_KEY);
    }

    public synchronized Map<String, Object> fetchPortfolio() {
        try {
            var rows = new ArrayList<JsonNode>();
            var cursors = new HashSet<String>();
            Map<String, Object> arguments = Map.of();
            for (int page = 0; page < 20; page++) {
                JsonNode payload = mcp.call("get_account_positions", arguments);
                JsonNode positions = payload.path("positions");
                if (!positions.isArray() || rows.size() + positions.size() > 10000) unsupportedData();
                positions.forEach(rows::add);
                String next = payload.path("nextCursor").asText(payload.path("next_cursor").asText(""));
                boolean more = payload.path("has_more").asBoolean(false) || payload.path("hasMore").asBoolean(false);
                if (next.isBlank() && !more) {
                    Map<String, Object> result = normalizePositions(rows, clock.instant());
                    attachAccountData(result);
                    try { store.write(CACHE_KEY, result); }
                    catch (IllegalStateException cacheFailure) {
                        result.put("message", result.get("message") + " 本地快照保存失败；当前数据仍为本次读取结果。");
                    }
                    return result;
                }
                // The currently documented positions tool returns all holdings. Only follow an explicit
                // cursor when the live input schema supports it; never silently call a guessed page API.
                if (next.isBlank() || next.length() > 8192 || !cursors.add(next) || !mcp.supportsArgument("get_account_positions", "cursor")) {
                    throw new IllegalStateException("IBKR 返回了未支持的分页格式，未展示不完整的持仓合计。");
                }
                arguments = Map.of("cursor", next);
            }
            throw new IllegalStateException("IBKR 持仓超过支持的分页数量，未展示不完整的合计。");
        } catch (IllegalStateException failure) {
            var cache = store.read(CACHE_KEY);
            if (cache.isPresent()) {
                try {
                    Instant savedAt = Instant.parse(String.valueOf(cache.get().get("asOf")));
                    if (savedAt.isAfter(clock.instant().minusSeconds(7 * 86400L)) && !savedAt.isAfter(clock.instant().plusSeconds(60))) {
                        Map<String, Object> fallback = new LinkedHashMap<>(cache.get());
                        fallback.put("stale", true);
                        fallback.put("message", "刷新失败，当前显示上次成功读取的持仓。" + safeFailure(failure).getMessage());
                        return fallback;
                    }
                } catch (Exception ignored) { /* Invalid/expired snapshots are never presented as current. */ }
            }
            throw safeFailure(failure);
        }
    }

    private void attachAccountData(Map<String, Object> result) {
        result.put("accountSummary", null);
        result.put("balances", List.of());
        result.put("summaryAvailable", false);
        result.put("balancesAvailable", false);
        // A temporarily unavailable cash endpoint must not discard successfully fetched holdings.
        try {
            if (mcp.supportsTool("get_account_summary")) {
                result.put("accountSummary", StockAccountData.summary(mcp.call("get_account_summary", Map.of())));
                result.put("summaryAvailable", true);
            }
        } catch (IllegalStateException ignored) { /* Return an explicit partial-success status below. */ }
        try {
            if (mcp.supportsTool("get_account_balances")) {
                result.put("balances", StockAccountData.balances(mcp.call("get_account_balances", Map.of())));
                result.put("balancesAvailable", true);
            }
        } catch (IllegalStateException ignored) { /* Missing balances are never turned into zero cash. */ }
        result.put("balanceMessage", Boolean.TRUE.equals(result.get("summaryAvailable")) && Boolean.TRUE.equals(result.get("balancesAvailable"))
                ? "账户概览按 IBKR 基准币种展示；现金按原币种分别展示。购买力不等于现金，不同币种不重复相加。"
                : "部分账户余额暂不可用；缺失金额显示为 —，不代表余额为零。可稍后刷新重试。");
        if (result.get("accountSummary") instanceof Map<?, ?> summary) recordNetValue(summary);
    }

    private void recordNetValue(Map<?, ?> summary) {
        if (!(summary.get("currency") instanceof String currency) || !(summary.get("netLiquidation") instanceof Number nav)) return;
        try {
            JsonNode previous = mapper.valueToTree(store.read(NAV_CACHE_KEY).orElse(Map.of()));
            List<Map<String, Object>> samples = new ArrayList<>();
            if (currency.equals(previous.path("currency").asText()) && previous.path("points").isArray()) {
                for (JsonNode sample : previous.path("points")) {
                    Instant time = Instant.parse(sample.path("time").asText());
                    if (time.isAfter(clock.instant().minusSeconds(2 * 86400L)) && time.isBefore(clock.instant().minusSeconds(30)))
                        samples.add(Map.of("time", time.toString(), "value", StockAccountData.decimal(sample.get("value"))));
                }
            }
            samples.add(Map.of("time", clock.instant().toString(), "value", nav));
            if (samples.size() > 512) samples = new ArrayList<>(samples.subList(samples.size()-512, samples.size()));
            store.write(NAV_CACHE_KEY, Map.of("currency", currency, "points", samples));
            historyCache.remove("PORTFOLIO:2H:account");
        } catch (RuntimeException ignored) { /* A local history write must not discard current broker balances. */ }
    }

    public synchronized void clearHistory() {
        historyCache.clear();
        performance = null;
        performanceFetchedAt = null;
    }

    /** Caller resolves a held position first; this adapter never accepts an arbitrary market-data URL. */
    public synchronized Map<String, Object> fetchHistory(String scope, String range, Map<?, ?> position, boolean refresh) {
        StockHistoryData.Range window = StockHistoryData.Range.parse(range);
        boolean account = "PORTFOLIO".equals(scope);
        if (!account && !"STOCK".equals(scope)) throw new IllegalArgumentException("Unknown history scope");
        if (!authorization.connected()) throw new IllegalStateException("请连接 IBKR 后查看走势。");
        String key = scope + ":" + range + ":" + (account ? "account" : String.valueOf(position.get("contractId")));
        CachedHistory cached = historyCache.get(key);
        if (!refresh && cached != null && cached.at().isAfter(clock.instant().minusSeconds(window.cacheSeconds))) return cached.data();
        try {
            Map<String, Object> result;
            if (account && window == StockHistoryData.Range.H2) {
                result = StockHistoryData.localAccount(mapper.valueToTree(store.read(NAV_CACHE_KEY).orElse(Map.of())), clock.instant());
            } else if (account) {
                if (refresh || performance == null || performanceFetchedAt.isBefore(clock.instant().minusSeconds(300))) {
                    performance = mcp.call("get_pa_performance_all_periods", Map.of());
                    performanceFetchedAt = clock.instant();
                }
                result = StockHistoryData.account(performance, window, performanceFetchedAt);
            } else {
                if (!(position.get("contractId") instanceof Number id) || id.longValue() <= 0) throw new IllegalArgumentException("Invalid stock contract");
                String asset = String.valueOf(position.get("assetClass"));
                if (!"STK".equals(asset)) return StockHistoryData.unavailable(scope, window, position, clock.instant(), "目前走势图支持股票和 ETF 持仓；该持仓类型暂不支持。");
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("contract_id", id.longValue());
                args.put("security_type", "STK");
                args.put("outside_rth", false);
                args.put("step", window.step);
                args.put("period", window.period);
                result = StockHistoryData.stock(mcp.call("get_price_history", args), window, position, clock.instant());
            }
            if (historyCache.size() >= 64 && !historyCache.containsKey(key)) historyCache.remove(historyCache.keySet().iterator().next());
            historyCache.put(key, new CachedHistory(clock.instant(), result));
            return result;
        } catch (IllegalStateException failure) {
            if (cached != null && cached.at().isAfter(clock.instant().minusSeconds(86400))) {
                Map<String, Object> stale = new LinkedHashMap<>(cached.data());
                stale.put("stale", true);
                stale.put("message", "走势更新失败，显示上次成功读取的历史数据；请注意数据时间。");
                return stale;
            }
            return StockHistoryData.unavailable(scope, window, position, clock.instant(),
                    "IBKR 暂未返回可用历史数据，可能受账户历史、交易时段或行情权限限制。请稍后刷新重试。");
        }
    }

    private record CachedHistory(Instant at, Map<String, Object> data) {}

    /** Market-only context: exact stock resolution, selected quote fields, and daily bars. */
    public synchronized Map<String, Object> fetchStockContext(String symbol, String exchange) {
        try { return stockContext(symbol, exchange); }
        catch (IllegalStateException failure) { throw safeFailure(failure); }
    }

    private Map<String, Object> stockContext(String symbol, String exchange) {
        String ticker = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        String venue = exchange == null ? "" : exchange.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ticker.matches("[A-Z0-9][A-Z0-9. /_-]{0,24}") || (!venue.isEmpty() && !venue.matches("[A-Z0-9._-]{1,30}"))) {
            throw new IllegalArgumentException("请输入有效的股票代码和交易所。");
        }
        JsonNode search = mcp.call("search_contracts", Map.of("query", ticker));
        if (!search.path("results").isArray()) unsupportedData();
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : search.path("results")) {
            boolean stock = false;
            for (JsonNode section : row.path("sections")) if ("STK".equals(section.path("security_type").asText())) stock = true;
            if (stock && ticker.equals(row.path("symbol").asText()) && (venue.isEmpty() || venue.equals("SMART")
                    || venue.equalsIgnoreCase(row.path("exchange").asText()))) matches.add(row);
        }
        if (matches.isEmpty()) throw new IllegalStateException("IBKR 未找到匹配股票。请检查代码和交易所。");
        if (matches.size() != 1) throw new IllegalStateException("该代码对应多个上市合约，请指定交易所后重试。");
        JsonNode contract = matches.get(0);
        if (!contract.path("underlying_contract_id").canConvertToLong() || contract.path("underlying_contract_id").asLong() <= 0) unsupportedData();
        long contractId = contract.path("underlying_contract_id").asLong();
        // Prefer the resolved native exchange; SMART is only used when explicitly selected.
        String resolvedVenue = !venue.isEmpty() ? venue : contract.path("exchange").asText("");
        Map<String, Object> quoteArgs = new LinkedHashMap<>();
        quoteArgs.put("contract_id", contractId);
        quoteArgs.put("market_data_names", List.of("last", "change", "prior_close", "volume", "high", "low"));
        if (!resolvedVenue.isBlank()) quoteArgs.put("exchange", resolvedVenue);
        JsonNode quote = mcp.call("get_price_snapshot", quoteArgs);
        Map<String, Object> historyArgs = new LinkedHashMap<>();
        historyArgs.put("contract_id", contractId);
        historyArgs.put("security_type", "STK");
        historyArgs.put("outside_rth", false);
        historyArgs.put("step", "ONE_DAY");
        historyArgs.put("period", "THREE_MONTHS");
        if (!resolvedVenue.isBlank()) historyArgs.put("exchange", resolvedVenue);
        JsonNode history = mcp.call("get_price_history", historyArgs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", ticker);
        result.put("name", text(contract, "description", 300));
        result.put("exchange", resolvedVenue.isBlank() ? null : resolvedVenue);
        result.put("asOf", clock.instant().toString());
        result.put("stale", false);
        result.put("source", "IBKR");
        result.put("currency", text(contract, "currency", 10));
        result.put("snapshot", normalizedQuote(quote));
        result.put("history", normalizedHistory(history));
        result.put("delayed", history.has("delayed") ? mapper.convertValue(history.get("delayed"), Object.class) : null);
        result.put("message", "行情按 IBKR 返回原样标记；数据缺失时不会补值。asOf 为本次读取时间。");
        return result;
    }

    static Map<String, Object> normalizePositions(List<JsonNode> rows, Instant retrievedAt) {
        List<Map<String, Object>> positions = new ArrayList<>();
        Map<String, Totals> totals = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            if (!row.isObject()) unsupportedData();
            String currency = text(row, "currency", 10);
            if (currency != null && !currency.matches("[A-Z]{3,10}")) unsupportedData();
            String contractId = row.path("contract_id").isNumber() ? row.path("contract_id").asText() : "row-" + i;
            String identity = contractId + ":" + currency + ":" + text(row, "asset_class", 20);
            if (!seen.add(identity)) throw new IllegalStateException("IBKR 返回了重复持仓，未重复计入合计。");
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("id", IbkrAuthorization.digest(identity));
            position.put("contractId", row.path("contract_id").isIntegralNumber() && row.path("contract_id").canConvertToLong() && row.path("contract_id").asLong() > 0 ? row.path("contract_id").asLong() : null);
            // Contract descriptions are not guaranteed to be ticker symbols (especially options).
            position.put("symbol", text(row, "symbol", 40));
            position.put("name", text(row, "contract_description", 300));
            position.put("currency", currency);
            position.put("assetClass", text(row, "asset_class", 20));
            position.put("quantity", number(row, "position"));
            position.put("averageCost", number(row, "average_price"));
            position.put("price", number(row, "market_price"));
            BigDecimal marketValue = number(row, "market_value");
            BigDecimal pnl = number(row, "unrealized_pnl");
            position.put("marketValue", marketValue);
            position.put("unrealizedPnl", pnl);
            positions.add(position);
            totals.computeIfAbsent(currency == null ? "" : currency, unused -> new Totals(currency)).add(marketValue, pnl);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asOf", retrievedAt.toString());
        result.put("stale", false);
        result.put("positions", positions);
        result.put("totals", totals.values().stream().map(Totals::value).toList());
        result.put("message", "已读取 IBKR 持仓；各币种分别合计，缺失字段保留为空。asOf 为本次读取时间。");
        return result;
    }

    private Map<String, Object> normalizedQuote(JsonNode quote) {
        if (!quote.isObject()) unsupportedData();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("last", "change", "prior-close", "volume", "high", "low")) {
            // MCP documents dynamic snapshot values. Preserve their provider shape instead of
            // assuming a scalar price, currency, delay or timestamp not supplied by the provider.
            if (quote.has(key)) result.put(key, mapper.convertValue(quote.get(key), Object.class));
        }
        if (result.isEmpty()) throw new IllegalStateException("IBKR 未返回可用行情字段，可能需要行情权限或数据订阅。");
        return result;
    }

    private static List<Map<String, Object>> normalizedHistory(JsonNode history) {
        JsonNode time = history.path("time");
        if (!time.isArray() || time.size() > 1000 || !history.path("error").asText("").isBlank()) unsupportedData();
        for (String key : List.of("open", "high", "low", "close", "volume")) {
            if (!history.path(key).isArray() || history.path(key).size() != time.size()) unsupportedData();
        }
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < time.size(); i++) {
            Map<String, Object> bar = new LinkedHashMap<>();
            if (!time.get(i).isTextual() || time.get(i).asText().length() > 80) unsupportedData();
            bar.put("time", time.get(i).asText());
            for (String key : List.of("open", "high", "low", "close", "volume")) bar.put(key, decimal(history.path(key).get(i)));
            bars.add(bar);
        }
        return bars;
    }

    private static String text(JsonNode row, String field, int max) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) return null;
        if (!value.isTextual() || value.asText().length() > max) unsupportedData();
        return value.asText();
    }
    private static BigDecimal number(JsonNode row, String field) { return decimal(row.get(field)); }
    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) unsupportedData();
        return value.decimalValue();
    }
    private static void unsupportedData() { throw new IllegalStateException("IBKR 返回了未支持的数据格式；未猜测或补充数值。"); }

    private static IllegalStateException safeFailure(IllegalStateException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        // Only locally generated messages reach this boundary; raw HTTP and MCP errors are discarded.
        if (message.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) return error;
        if (message.contains("registration was rejected") || message.contains("register a compatible") || message.contains("accept the local callback"))
            return new IllegalStateException("IBKR 暂未接受此本地应用的注册或回调地址，账户尚未连接。请稍后重试或联系 IBKR 确认自定义 MCP 客户端权限。");
        if (message.contains("state did not match") || message.contains("authorization code"))
            return new IllegalStateException("IBKR 授权已过期或回调校验失败，请重新点击连接。");
        if (message.contains("was not granted")) return new IllegalStateException("IBKR 授权未完成，请重新连接并同意只读访问。");
        if (message.contains("session expired")) return new IllegalStateException("IBKR 会话已过期，请再次刷新以建立新会话。");
        if (message.contains("Connect your") || message.contains("authorization expired") || message.contains("authorization is unavailable"))
            return new IllegalStateException("请连接或重新授权 IBKR 账户，再读取持仓和行情。");
        if (message.contains("schema") || message.contains("parameters") || message.contains("read tool") || message.contains("protocol"))
            return new IllegalStateException("IBKR 当前工具接口格式暂不支持，未猜测参数或补充数据。请更新适配器后重试。");
        if (message.contains("permissions beyond")) return new IllegalStateException("IBKR 返回的授权包含只读范围之外的权限，已拒绝保存，请重新授权。");
        return new IllegalStateException("IBKR 连接或数据读取失败，请稍后重试；如仍失败，请重新授权账户。");
    }

    private static final class Totals {
        private final String currency;
        private BigDecimal marketValue = BigDecimal.ZERO;
        private BigDecimal pnl = BigDecimal.ZERO;
        Totals(String currency) { this.currency = currency; }
        void add(BigDecimal value, BigDecimal unrealized) {
            marketValue = marketValue == null || value == null ? null : marketValue.add(value);
            pnl = pnl == null || unrealized == null ? null : pnl.add(unrealized);
        }
        Map<String, Object> value() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("currency", currency);
            result.put("marketValue", currency == null ? null : marketValue);
            result.put("unrealizedPnl", currency == null ? null : pnl);
            return result;
        }
    }
}
