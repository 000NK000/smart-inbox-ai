package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** Select only documented monetary fields. Account identifiers and raw broker output stay private. */
final class StockAccountData {
    private StockAccountData() {}

    static Map<String, Object> summary(JsonNode payload) {
        if (!payload.isObject()) invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currency", currency(payload.path("currency"), false));
        fields(payload, result, Map.of("net_liquidation", "netLiquidation", "total_cash_value", "totalCash",
                "available_funds", "availableFunds", "buying_power", "buyingPower",
                "gross_position_value", "grossPositionValue", "equity_with_loan_value", "equityWithLoanValue",
                "excess_liquidity", "excessLiquidity", "initial_margin", "initialMargin", "maintenance_margin", "maintenanceMargin"));
        if (result.values().stream().allMatch(Objects::isNull)) invalid();
        return result;
    }

    static List<Map<String, Object>> balances(JsonNode payload) {
        JsonNode rows = payload.path("balances");
        if (!rows.isArray() || rows.size() > 100) invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> currencies = new HashSet<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) invalid();
            String currency = currency(row.path("currency"), true);
            // BASE is a broker-converted aggregate, not another currency balance to add.
            if ("BASE".equals(currency)) continue;
            if (currency != null && !currencies.add(currency)) invalid();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("currency", currency);
            fields(row, item, Map.of("cash_balance", "cashBalance", "settled_cash", "settledCash",
                    "stock_market_value", "stockMarketValue", "net_liquidation_value", "netLiquidationValue",
                    "unrealized_pnl", "unrealizedPnl", "realized_pnl", "realizedPnl", "exchange_rate", "exchangeRate"));
            result.add(item);
        }
        return result;
    }

    static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) invalid();
        return value.decimalValue();
    }

    static String currency(JsonNode value, boolean allowBase) {
        if (value == null || value.isNull() || value.isMissingNode() || value.asText().isBlank()) return null;
        if (!value.isTextual() || !(value.asText().matches("[A-Z]{3}") || allowBase && "BASE".equals(value.asText()))) invalid();
        return value.asText();
    }

    private static void fields(JsonNode source, Map<String, Object> target, Map<String, String> names) {
        names.forEach((broker, client) -> target.put(client, decimal(source.get(broker))));
    }

    static void invalid() { throw new IllegalStateException("IBKR 返回了未支持的数据格式；未猜测或补充数值。"); }
}
