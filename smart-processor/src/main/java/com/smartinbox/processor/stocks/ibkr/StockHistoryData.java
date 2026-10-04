package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Validates broker time-series arrays without interpolating missing prices or account values. */
final class StockHistoryData {
    enum Range {
        H2("2H", "ONE_DAY", "ONE_MIN", "1D", Duration.ofHours(2), "1min", 60),
        D1("1D", "ONE_DAY", "FIVE_MINS", "1D", Duration.ofDays(1), "5min", 60),
        W1("1W", "ONE_WEEK", "ONE_HOUR", "7D", Duration.ofDays(7), "1h", 300),
        // Daily endpoints are inclusive: the latest day plus the preceding fourteen days.
        D15("15D", "ONE_MONTH", "ONE_DAY", "1M", Duration.ofDays(14), "1d", 300),
        M1("1M", "ONE_MONTH", "ONE_DAY", "1M", Duration.ofDays(31), "1d", 300),
        Y1("1Y", "ONE_YEAR", "ONE_DAY", "1Y", Duration.ofDays(366), "1d", 300);
        final String value, period, step, accountPeriod, resolution;
        final Duration duration;
        final long cacheSeconds;
        Range(String value, String period, String step, String accountPeriod, Duration duration, String resolution, long cacheSeconds) {
            this.value=value;this.period=period;this.step=step;this.accountPeriod=accountPeriod;
            this.duration=duration;this.resolution=resolution;this.cacheSeconds=cacheSeconds;
        }
        static Range parse(String input) {
            return Arrays.stream(values()).filter(value -> value.value.equals(input)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown history range"));
        }
    }

    private StockHistoryData() {}

    static Map<String, Object> localAccount(JsonNode data, Instant asOf) {
        String currency = StockAccountData.currency(data.path("currency"), false);
        Map<String, Object> result = base("PORTFOLIO", Range.H2, currency == null ? Map.of() : Map.of("currency", currency), asOf);
        result.put("source", "IBKR · local snapshots");
        result.put("resolution", "snapshot");
        List<Map<String, Object>> points = new ArrayList<>();
        JsonNode samples = data.path("points");
        if (!samples.isMissingNode() && (!samples.isArray() || samples.size() > 512)) StockAccountData.invalid();
        Instant previous = null;
        for (JsonNode row : samples) {
            Instant time = parseTime(row.path("time"), false);
            if (previous != null && !time.isAfter(previous)) StockAccountData.invalid();
            previous = time;
            if (currency != null && !time.isBefore(asOf.minusSeconds(7200)) && !time.isAfter(asOf))
                points.add(point(time, StockAccountData.decimal(row.get("value"))));
        }
        complete(result, points, "自本功能启用后，仅在刷新资产成功时记录账户净值；这是最近 2 小时的本地真实快照，不代表完整盘中行情。净值变化也受出入金影响。");
        if (points.isEmpty()) result.put("message", "最近 2 小时暂无账户净值记录。刷新资产后会开始记录真实快照；系统不会补造过去的盘中走势。");
        return result;
    }

    static Map<String, Object> account(JsonNode payload, Range range, Instant asOf) {
        JsonNode accounts = payload.path("accounts");
        if (!accounts.isObject() || accounts.size() != 1) return unavailable("PORTFOLIO", range, Map.of(), asOf,
                "IBKR 未提供单一账户的完整历史；为避免混合不同账户或币种，暂不合并走势图。");
        JsonNode account = accounts.elements().next();
        String currency = StockAccountData.currency(account.path("base_currency"), false);
        if (currency == null) StockAccountData.invalid();
        JsonNode period = account.path("periods").path(range.accountPeriod);
        if (!period.isObject()) return unavailable("PORTFOLIO", range, Map.of("currency", currency), asOf,
                "IBKR 暂未提供所选时间范围的账户历史。");
        if (!"D".equals(period.path("frequency").asText())) return unavailable("PORTFOLIO", range, Map.of("currency", currency), asOf,
                "IBKR 返回的账户历史频率暂不支持，未推算或补齐数据。");
        if (range == Range.H2) return unavailable("PORTFOLIO", range, Map.of("currency", currency), asOf,
                "IBKR 当前提供每日账户净值，无法显示真实的 2 小时账户走势；可选择更长范围，或查看单只股票的分时行情。");
        JsonNode dates = period.path("dates"), nav = period.path("nav"), returns = period.path("cps");
        requireParallel(dates, nav);
        if (!returns.isMissingNode() && (!returns.isArray() || returns.size() != dates.size())) StockAccountData.invalid();
        List<Map<String, Object>> points = new ArrayList<>();
        Instant previous = null;
        for (int i=0; i<dates.size(); i++) {
            Instant time = parseTime(dates.get(i), true);
            if (previous != null && !time.isAfter(previous)) StockAccountData.invalid();
            previous = time;
            points.add(point(time, StockAccountData.decimal(nav.get(i))));
        }
        points = trim(points, range.duration);
        Map<String, Object> result = base("PORTFOLIO", range, Map.of("currency", currency), asOf);
        result.put("resolution", "1d");
        String measure = payload.path("portfolio_measure").asText("");
        if (Set.of("TWR", "MWR").contains(measure)) result.put("portfolioMeasure", measure);
        complete(result, points, "账户净值包含现金和持仓，由 IBKR 按账户基准币种提供。净值变化也受出入金影响，不等于投资收益率；仅显示实际可用的历史日期。");
        return result;
    }

    static Map<String, Object> stock(JsonNode payload, Range range, Map<?, ?> position, Instant asOf) {
        if (!payload.path("error").asText("").isBlank()) StockAccountData.invalid();
        JsonNode times = payload.path("time"), close = payload.path("close");
        requireParallel(times, close);
        List<Map<String, Object>> points = new ArrayList<>();
        Instant previous = null;
        boolean daily = "1d".equals(range.resolution);
        for (int i=0; i<times.size(); i++) {
            Instant time = parseTime(times.get(i), daily);
            if (previous != null && !time.isAfter(previous)) StockAccountData.invalid();
            previous = time;
            points.add(point(time, StockAccountData.decimal(close.get(i))));
        }
        points = trim(points, range.duration);
        Map<String, Object> result = base("STOCK", range, position, asOf);
        if (payload.path("delayed").isNumber()) result.put("delayed", payload.path("delayed").intValue());
        complete(result, points, "显示 IBKR 返回的历史收盘价，仅包含常规交易时段。时间范围截至最近可用行情；休市或缺失日期不补值，行情可能延迟。");
        return result;
    }

    static Map<String, Object> unavailable(String scope, Range range, Map<?, ?> position, Instant asOf, String message) {
        Map<String, Object> result = base(scope, range, position, asOf);
        result.put("available", false);
        result.put("points", List.of());
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> base(String scope, Range range, Map<?, ?> position, Instant asOf) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("range", range.value);
        result.put("symbol", position.get("symbol") == null ? position.get("name") : position.get("symbol"));
        result.put("currency", position.get("currency"));
        result.put("metric", "PORTFOLIO".equals(scope) ? "NAV" : "PRICE");
        result.put("source", "IBKR");
        result.put("asOf", asOf.toString());
        result.put("stale", false);
        result.put("resolution", "PORTFOLIO".equals(scope) ? "1d" : range.resolution);
        result.put("dataFrom", null);
        result.put("dataTo", null);
        return result;
    }

    private static void complete(Map<String, Object> result, List<Map<String, Object>> points, String explanation) {
        long count = points.stream().filter(point -> point.get("value") != null).count();
        result.put("available", count > 0);
        result.put("points", points);
        if (!points.isEmpty()) {
            result.put("dataFrom", points.get(0).get("time"));
            result.put("dataTo", points.get(points.size()-1).get("time"));
        }
        result.put("message", count == 0 ? "该范围内暂无可用历史数值；未推算或补齐数据。" :
                (count == 1 ? "该范围内只有一个真实数据点，暂时无法形成折线。" : "") + explanation);
    }

    private static Map<String, Object> point(Instant time, BigDecimal value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("time", time.toString());
        result.put("value", value);
        return result;
    }

    private static List<Map<String, Object>> trim(List<Map<String, Object>> points, Duration duration) {
        if (points.isEmpty()) return points;
        Instant cutoff = Instant.parse((String) points.get(points.size()-1).get("time")).minus(duration);
        return points.stream().filter(point -> !Instant.parse((String) point.get("time")).isBefore(cutoff)).toList();
    }

    private static void requireParallel(JsonNode times, JsonNode values) {
        if (!times.isArray() || !values.isArray() || times.size() != values.size() || times.size() > 10000) StockAccountData.invalid();
    }

    private static Instant parseTime(JsonNode value, boolean daily) {
        if (!value.isTextual() || value.asText().length() > 80) { StockAccountData.invalid(); }
        String text = value.asText();
        try {
            if (daily && text.matches("\\d{8}")) return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (daily && text.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) { StockAccountData.invalid(); return null; }
    }
}
