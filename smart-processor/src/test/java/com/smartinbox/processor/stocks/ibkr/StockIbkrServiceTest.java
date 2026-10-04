package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.stocks.StockSecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockIbkrServiceTest {
    private static final String CALLBACK = "http://127.0.0.1:8083/api/stocks/auth/ibkr/callback";
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"), ZoneOffset.UTC);
    private final Map<String, Map<String, Object>> secrets = new HashMap<>();
    private StockSecretStore store;
    private FakeHttp http;
    private StockIbkrService service;

    @BeforeEach void setup() {
        store = mock(StockSecretStore.class);
        when(store.read(anyString())).thenAnswer(invocation -> Optional.ofNullable(secrets.get(invocation.getArgument(0))));
        doAnswer(invocation -> { secrets.put(invocation.getArgument(0), new LinkedHashMap<>(invocation.<Map<String, Object>>getArgument(1))); return null; })
                .when(store).write(anyString(), anyMap());
        doAnswer(invocation -> { secrets.remove(invocation.getArgument(0)); return null; }).when(store).delete(anyString());
        http = new FakeHttp();
        service = new StockIbkrService(json, store, http, clock, CALLBACK);
    }

    @Test void authorizationUsesIndependentPublicClientPkceAndReadOnlyScope() {
        String url = (String) service.beginAuthorization().get("url");
        Map<String, String> params = query(URI.create(url).getRawQuery());
        assertEquals("https://api.ibkr.com/oauth2/authorize", url.split("\\?")[0]);
        assertEquals("mcp.read", params.get("scope"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals(CALLBACK, params.get("redirect_uri"));
        assertEquals(IbkrAuthorization.RESOURCE.toString(), params.get("resource"));
        assertEquals("none", http.registration.path("token_endpoint_auth_method").asText());
        assertEquals("mcp.read", http.registration.path("scope").asText());
        assertFalse((boolean) service.status().get("connected"));
        service.finishAuthorization(Map.of("state", params.get("state"), "code", "example-code"));
        assertEquals(params.get("code_challenge"), IbkrAuthorization.digest(http.lastTokenForm.get("code_verifier")));
        assertTrue((boolean) service.status().get("connected"));
        assertEquals("access-one", secrets.get(IbkrAuthorization.TOKEN_KEY).get("access_token"));
        assertFalse(service.status().toString().contains("access-one"));
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", params.get("state"), "code", "example-code")));
        assertEquals(1, http.tokenCalls);
    }

    @Test void stateMismatchDoesNotExchangeCodeAndProviderCancellationIsSingleUse() {
        String state = start();
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", "wrong", "code", "sensitive")));
        assertEquals(0, http.tokenCalls);
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", state, "error", "access_denied")));
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", state, "code", "sensitive")));
        assertEquals(0, http.tokenCalls);
    }

    @Test void metadataCannotRedirectAuthorizationToAnUntrustedHost() {
        http.authorizationEndpoint = "https://attacker.example/oauth";
        assertThrows(IllegalStateException.class, service::beginAuthorization);
        assertNull(http.registration);
        assertFalse((boolean) service.status().get("connected"));
        assertThrows(IllegalStateException.class, () -> IbkrHttp.validateEndpoint(URI.create("https://api.ibkr.com@attacker.example/token")));
        assertThrows(IllegalStateException.class, () -> IbkrHttp.validateEndpoint(URI.create("http://api.ibkr.com/token")));
    }

    @Test void rejectedRegistrationReportsAnActionableErrorAndNeverConnects() {
        http.registrationStatus = 403;
        IllegalStateException error = assertThrows(IllegalStateException.class, service::beginAuthorization);
        assertTrue(error.getMessage().contains("本地应用"));
        assertFalse(error.getMessage().contains("sensitive"));
        assertFalse((boolean) service.status().get("connected"));
    }

    @Test void tokenWithWriteScopeIsRejected() {
        String state = start();
        http.tokenScope = "mcp.read mcp.write";
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", state, "code", "example")));
        assertFalse(secrets.containsKey(IbkrAuthorization.TOKEN_KEY));
    }

    @Test void invalidBearerCharactersNeverReachAnAuthorizationHeader() {
        String state = start();
        http.firstAccessToken = "invalid\r\nInjected: value";
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", state, "code", "example")));
        assertFalse(secrets.containsKey(IbkrAuthorization.TOKEN_KEY));
        assertTrue(http.calledTools.isEmpty());
    }

    @Test void refreshRotatesTokenAndKeepsOnlyReadScope() {
        connect();
        secrets.get(IbkrAuthorization.TOKEN_KEY).put("expires_at", clock.instant().getEpochSecond() + 20);
        service.fetchPortfolio();
        assertEquals(2, http.tokenCalls);
        assertEquals("refresh_token", http.lastTokenForm.get("grant_type"));
        assertEquals("mcp.read", http.lastTokenForm.get("scope"));
        assertEquals("refresh-one", http.lastTokenForm.get("refresh_token"));
        assertEquals("refresh-two", secrets.get(IbkrAuthorization.TOKEN_KEY).get("refresh_token"));
    }

    @Test void positionsRetainMissingValuesSeparateCurrenciesAndNeverExposeAccountIdentifiers() {
        connect();
        Map<String, Object> portfolio = service.fetchPortfolio();
        List<Map<String, Object>> positions = castRows(portfolio.get("positions"));
        List<Map<String, Object>> totals = castRows(portfolio.get("totals"));
        assertEquals(3, positions.size());
        assertNull(positions.get(0).get("symbol"));
        assertNull(positions.get(2).get("unrealizedPnl"));
        assertEquals(new BigDecimal("-2"), positions.get(2).get("quantity"));
        assertEquals(2, totals.size());
        assertEquals("USD", totals.get(0).get("currency"));
        assertEquals(new BigDecimal("80"), totals.get(0).get("marketValue"));
        assertNull(totals.get(0).get("unrealizedPnl"));
        assertEquals(new BigDecimal("300"), totals.get(1).get("marketValue"));
        assertFalse(portfolio.toString().contains("U123SECRET"));
        assertFalse((boolean) portfolio.get("stale"));
        assertEquals(List.of("get_account_positions", "get_account_summary", "get_account_balances"), http.calledTools);
        assertEquals(2, http.toolListCalls);
        assertTrue(http.sessionHeaderObserved);
    }

    @Test void failedRefreshReturnsLabeledSnapshotAndDisconnectRemovesIt() {
        connect();
        Map<String, Object> first = service.fetchPortfolio();
        http.failData = true;
        Map<String, Object> fallback = service.fetchPortfolio();
        assertEquals(first.get("asOf"), fallback.get("asOf"));
        assertTrue((boolean) fallback.get("stale"));
        assertTrue(fallback.get("message").toString().contains("上次"));
        service.disconnect();
        assertFalse(secrets.containsKey(IbkrAuthorization.TOKEN_KEY));
        assertFalse(secrets.containsKey(StockIbkrService.CACHE_KEY));
        assertFalse(secrets.containsKey(StockIbkrService.NAV_CACHE_KEY));
        assertThrows(IllegalStateException.class, service::fetchPortfolio);
    }

    @Test void balanceEndpointFailureDoesNotDiscardHoldingsOrPretendCashIsZero() {
        connect();
        http.failBalances = true;
        var data = service.fetchPortfolio();
        assertEquals(3, castRows(data.get("positions")).size());
        assertEquals(true, data.get("summaryAvailable"));
        assertEquals(false, data.get("balancesAvailable"));
        assertEquals(List.of(), data.get("balances"));
        assertFalse((boolean) data.get("stale"));
        assertTrue(data.get("balanceMessage").toString().contains("不代表余额为零"));
        assertTrue(secrets.containsKey(StockIbkrService.NAV_CACHE_KEY));
    }

    @Test void localTwoHourNavStartsWithRealSnapshotAndIsRemovedAfterNewAuthorization() {
        connect();
        service.fetchPortfolio();
        var history = service.fetchHistory("PORTFOLIO", "2H", Map.of(), false);
        assertEquals("IBKR · local snapshots", history.get("source"));
        assertEquals(1, castRows(history.get("points")).size());
        assertEquals(0, new BigDecimal("200").compareTo((BigDecimal) castRows(history.get("points")).get(0).get("value")));
        assertFalse(http.calledTools.contains("get_pa_performance_all_periods"));
        connect();
        var changedAccount = service.fetchHistory("PORTFOLIO", "2H", Map.of(), false);
        assertEquals(false, changedAccount.get("available"));
        assertEquals(List.of(), changedAccount.get("points"));
    }

    @Test void stockHistoryUsesOnlyOnePeriodAndReusesCacheUntilExplicitRefresh() {
        connect();
        Map<String, Object> position = Map.of("contractId", 1L, "assetClass", "STK", "symbol", "EXAMPLE", "currency", "USD");
        var first = service.fetchHistory("STOCK", "1M", position, false);
        assertEquals(true, first.get("available"));
        service.fetchHistory("STOCK", "1M", position, false);
        assertEquals(1, http.calledTools.stream().filter("get_price_history"::equals).count());
        assertEquals("ONE_MONTH", http.lastHistoryArguments.path("period").asText());
        assertFalse(http.lastHistoryArguments.has("step_count"));
        http.failData = true;
        var stale = service.fetchHistory("STOCK", "1M", position, true);
        assertEquals(true, stale.get("stale"));
        assertEquals(first.get("points"), stale.get("points"));
    }

    @Test void portfolioRangesReuseProviderHistoryWithoutCombiningAccountsOrMakingMoreAiCalls() {
        connect();
        var monthly = service.fetchHistory("PORTFOLIO", "1M", Map.of(), false);
        var annual = service.fetchHistory("PORTFOLIO", "1Y", Map.of(), false);
        assertEquals(true, monthly.get("available"));
        assertEquals(monthly.get("points"), annual.get("points"));
        assertEquals(1, http.calledTools.stream().filter("get_pa_performance_all_periods"::equals).count());
        assertFalse(monthly.toString().contains("U123SECRET"));
    }

    @Test void unknownCurrenciesCannotBeAddedTogetherAndMissingValuesRemainNull() throws Exception {
        Map<String, Object> result = StockIbkrService.normalizePositions(List.of(
                json.readTree("{\"contract_id\":1,\"market_value\":100,\"unrealized_pnl\":3}"),
                json.readTree("{\"contract_id\":2,\"market_value\":200,\"unrealized_pnl\":7}")), clock.instant());
        Map<String, Object> total = castRows(result.get("totals")).get(0);
        assertNull(total.get("currency"));
        assertNull(total.get("marketValue"));
        assertNull(total.get("unrealizedPnl"));
        assertNull(castRows(result.get("positions")).get(0).get("quantity"));
        assertEquals("2026-09-30T15:00:00Z", result.get("asOf"));
    }

    @Test void unknownRequiredArgumentsFailBeforeCallingAnyAccountTool() {
        connect();
        http.unknownRequired = true;
        assertThrows(IllegalStateException.class, service::fetchPortfolio);
        assertTrue(http.calledTools.isEmpty());
    }

    @Test void incompleteHoldingsPaginationDoesNotProducePartialTotals() {
        connect();
        http.positionsHasMore = true;
        assertThrows(IllegalStateException.class, service::fetchPortfolio);
        assertFalse(secrets.containsKey(StockIbkrService.CACHE_KEY));
    }

    @Test void exactStockResolutionProducesQuotesAndAlignedDailyHistory() {
        connect();
        Map<String, Object> context = service.fetchStockContext("AAPL", "NASDAQ");
        assertEquals("AAPL", context.get("symbol"));
        assertNull(context.get("currency"));
        assertEquals(2, castRows(context.get("history")).size());
        assertEquals(List.of("search_contracts", "get_price_snapshot", "get_price_history"), http.calledTools);
        assertEquals(265598, http.lastHistoryArguments.path("contract_id").asInt());
        assertEquals("THREE_MONTHS", http.lastHistoryArguments.path("period").asText());
        assertEquals("NASDAQ", http.lastHistoryArguments.path("exchange").asText());
        assertFalse(context.toString().contains("U123SECRET"));
    }

    @Test void ambiguousSymbolsAreNotSilentlyMappedToTheFirstContract() {
        connect();
        http.ambiguousSearch = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.fetchStockContext("AAPL", ""));
        assertTrue(failure.getMessage().contains("多个"));
        assertEquals(List.of("search_contracts"), http.calledTools);
    }

    @Test void malformedHistoryIsNotRepairedWithInventedPrices() {
        connect();
        http.malformedHistory = true;
        assertThrows(IllegalStateException.class, () -> service.fetchStockContext("AAPL", "NASDAQ"));
    }

    @Test void closedAllowlistCannotInvokeWriteTools() {
        connect();
        var auth = new IbkrAuthorization(json, store, http, clock, CALLBACK);
        var client = new IbkrMcp(json, http, auth);
        assertThrows(IllegalStateException.class, () -> client.call("create_order_instruction", Map.of()));
        assertTrue(http.calledTools.isEmpty());
    }

    @Test void boundedTransportCancelsOversizeResponses() {
        var subscriber = new IbkrHttp.LimitedBody();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(IbkrHttp.MAX_BYTES + 1)));
        verify(subscription).cancel();
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    private String start() { return query(URI.create((String) service.beginAuthorization().get("url")).getRawQuery()).get("state"); }
    private void connect() { service.finishAuthorization(Map.of("state", start(), "code", "example-code")); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> castRows(Object value) { return (List<Map<String, Object>>) value; }
    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private final class FakeHttp implements IbkrHttp {
        JsonNode registration;
        Map<String, String> lastTokenForm;
        JsonNode lastHistoryArguments;
        int tokenCalls;
        int toolListCalls;
        int registrationStatus = 201;
        String tokenScope = "mcp.read";
        String firstAccessToken = "access-one";
        String authorizationEndpoint = "https://api.ibkr.com/oauth2/authorize";
        boolean sessionHeaderObserved;
        boolean failData;
        boolean unknownRequired;
        boolean positionsHasMore;
        boolean ambiguousSearch;
        boolean malformedHistory;
        boolean failBalances;
        final List<String> calledTools = new ArrayList<>();

        @Override public Reply send(String method, URI uri, Map<String, String> headers, String body) {
            IbkrHttp.validateEndpoint(uri);
            if (uri.equals(IbkrAuthorization.RESOURCE_METADATA)) return plain(200, Map.of("resource", IbkrAuthorization.RESOURCE.toString(),
                    "authorization_servers", List.of("https://api.ibkr.com"), "scopes_supported", List.of("mcp.read", "mcp.write"), "bearer_methods_supported", List.of("header")));
            if (uri.equals(IbkrAuthorization.SERVER_METADATA)) return plain(200, Map.of("issuer", "https://api.ibkr.com", "authorization_endpoint", authorizationEndpoint,
                    "token_endpoint", "https://api.ibkr.com/oauth2/api/v1/token", "registration_endpoint", "https://api.ibkr.com/oauth2/register",
                    "response_types_supported", List.of("code"), "grant_types_supported", List.of("authorization_code", "refresh_token"),
                    "token_endpoint_auth_methods_supported", List.of("none"), "code_challenge_methods_supported", List.of("S256"), "scopes_supported", List.of("mcp.read")));
            if (uri.getPath().equals("/oauth2/register")) {
                registration = tree(body);
                return plain(registrationStatus, Map.of("client_id", "local-client", "token_endpoint_auth_method", "none", "redirect_uris", List.of(CALLBACK)));
            }
            if (uri.getPath().equals("/oauth2/api/v1/token")) {
                tokenCalls++;
                lastTokenForm = query(body);
                return plain(200, Map.of("access_token", tokenCalls == 1 ? firstAccessToken : "access-two", "refresh_token", tokenCalls == 1 ? "refresh-one" : "refresh-two",
                        "token_type", "Bearer", "expires_in", 3600, "scope", tokenScope));
            }
            assertEquals(IbkrAuthorization.RESOURCE, uri);
            assertTrue(headers.get("Authorization").startsWith("Bearer access-"));
            JsonNode request = tree(body);
            long id = request.path("id").asLong();
            String operation = request.path("method").asText();
            if (operation.equals("initialize")) {
                String data = "data: " + json.valueToTree(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of())))) + "\n\n";
                return new Reply(200, Map.of("Mcp-Session-Id", List.of("test-session")), data);
            }
            sessionHeaderObserved |= "test-session".equals(headers.get("Mcp-Session-Id"));
            if (operation.equals("notifications/initialized")) return plain(202, Map.of());
            if (operation.equals("tools/list")) {
                toolListCalls++;
                if (!request.path("params").has("cursor")) return rpc(id, Map.of("tools", List.of(tool("get_account_positions", unknownRequired
                        ? "{\"type\":\"object\",\"properties\":{\"unexpected\":{\"type\":\"string\"}},\"required\":[\"unexpected\"]}"
                        : "{\"type\":\"object\",\"properties\":{}}")), "nextCursor", "second"));
                return rpc(id, Map.of("tools", List.of(tool("get_account_summary", "{\"type\":\"object\",\"properties\":{}}"),
                        tool("get_account_balances", "{\"type\":\"object\",\"properties\":{}}"),
                        tool("get_pa_performance_all_periods", "{\"type\":\"object\",\"properties\":{}}"),
                        tool("search_contracts", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"),
                        tool("get_price_snapshot", "{\"type\":\"object\",\"properties\":{\"contract_id\":{\"type\":\"number\"},\"exchange\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]},\"market_data_names\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"contract_id\"]}"),
                        tool("get_price_history", "{\"type\":\"object\",\"properties\":{\"contract_id\":{\"type\":\"number\"},\"exchange\":{\"type\":\"string\"},\"security_type\":{\"type\":\"string\",\"enum\":[\"STK\"]},\"step\":{\"type\":\"string\"},\"period\":{\"type\":\"string\"},\"outside_rth\":{\"type\":\"boolean\"}},\"required\":[\"contract_id\",\"security_type\",\"step\",\"outside_rth\"]}"))));
            }
            assertEquals("tools/call", operation);
            String name = request.path("params").path("name").asText();
            calledTools.add(name);
            if (failData) return plain(503, Map.of("error", "sensitive-provider-details"));
            Object data;
            if (name.equals("get_account_positions")) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("account_id", "U123SECRET");
                payload.put("positions", List.of(
                        Map.of("contract_id", 1, "currency", "USD", "contract_description", "Apple", "position", 1, "market_value", 100, "unrealized_pnl", 10),
                        Map.of("contract_id", 2, "currency", "CAD", "contract_description", "Canadian stock", "position", 3, "market_value", 300, "unrealized_pnl", 20),
                        Map.of("contract_id", 3, "currency", "USD", "contract_description", "Short", "position", -2, "market_value", -20)));
                if (positionsHasMore) payload.put("has_more", true);
                data = payload;
            } else if (name.equals("get_account_summary")) data = Map.of("currency", "USD", "net_liquidation", 200, "total_cash_value", 100);
            else if (name.equals("get_account_balances")) {
                if (failBalances) return plain(503, Map.of("error", "private details"));
                data = Map.of("balances", List.of(Map.of("currency", "USD", "cash_balance", 100)));
            } else if (name.equals("get_pa_performance_all_periods")) {
                var period = Map.of("frequency", "D", "dates", List.of("20260929", "20260930"), "nav", List.of(190, 200), "cps", List.of(0, 0.05));
                data = Map.of("portfolio_measure", "TWR", "included_accounts", List.of("U123SECRET"), "accounts", Map.of("account",
                        Map.of("base_currency", "USD", "periods", Map.of("1M", period, "1Y", period))));
            } else if (name.equals("search_contracts")) {
                var row = Map.of("symbol", "AAPL", "exchange", "NASDAQ", "description", "Apple Inc.", "underlying_contract_id", 265598, "sections", List.of(Map.of("security_type", "STK")));
                data = Map.of("results", ambiguousSearch ? List.of(row, row) : List.of(row));
            } else if (name.equals("get_price_snapshot")) data = Map.of("last", 100, "prior-close", 99, "volume", 1000);
            else if (name.equals("get_price_history")) {
                lastHistoryArguments = request.path("params").path("arguments");
                data = Map.of("time", List.of("2026-09-29", "2026-09-30"), "open", List.of(98, 99), "high", List.of(100, 101), "low", List.of(97, 98),
                        "close", malformedHistory ? List.of(99) : List.of(99, 100), "volume", List.of(100, 120), "delayed", 0, "error", "");
            } else throw new AssertionError("Unexpected tool " + name);
            return rpc(id, Map.of("structuredContent", data, "isError", false));
        }

        private Map<String, Object> tool(String name, String schema) { return Map.of("name", name, "inputSchema", tree(schema), "annotations", Map.of("readOnlyHint", true)); }
        private Reply rpc(long id, Object result) { return plain(200, Map.of("jsonrpc", "2.0", "id", id, "result", result)); }
        private Reply plain(int status, Object value) { return new Reply(status, Map.of(), json.valueToTree(value).toString()); }
        private JsonNode tree(String input) { try { return json.readTree(input); } catch (Exception invalid) { throw new AssertionError(invalid); } }
    }
}
