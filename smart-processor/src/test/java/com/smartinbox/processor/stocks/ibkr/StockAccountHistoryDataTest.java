package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockAccountHistoryDataTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-02T02:00:00Z");
    private final Map<String, Object> stock = Map.of("contractId", 123L, "symbol", "EXAMPLE", "currency", "USD", "assetClass", "STK");
    private JsonNode tree(String value) throws Exception { return json.readTree(value); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> points(Map<String, Object> response) { return (List<Map<String, Object>>) response.get("points"); }

    @Test void accountCashUsesBrokerBaseCurrencyAndDoesNotCountBaseAsAnotherCurrency() throws Exception {
        var summary = StockAccountData.summary(tree("""
                {"currency":"USD","net_liquidation":250,"total_cash_value":150,"available_funds":120,"buying_power":240,"account_id":"PRIVATE"}
                """));
        assertEquals(new BigDecimal("250"), summary.get("netLiquidation"));
        assertEquals(new BigDecimal("150"), summary.get("totalCash"));
        assertNull(summary.get("initialMargin"));
        assertFalse(summary.toString().contains("PRIVATE"));
        var balances = StockAccountData.balances(tree("""
                {"balances":[{"currency":"BASE","cash_balance":150},{"currency":"USD","cash_balance":100,"settled_cash":90},{"currency":"CAD","cash_balance":70}]}
                """));
        assertEquals(List.of("USD", "CAD"), balances.stream().map(row -> row.get("currency")).toList());
        assertEquals(new BigDecimal("70"), balances.get(1).get("cashBalance"));
        assertNull(balances.get(1).get("settledCash"));
    }

    @Test void duplicateCurrenciesAndNonNumericValuesAreNotSilentlySummed() throws Exception {
        JsonNode duplicate = tree("{\"balances\":[{\"currency\":\"USD\"},{\"currency\":\"USD\"}]}");
        assertThrows(IllegalStateException.class, () -> StockAccountData.balances(duplicate));
        JsonNode malformed = tree("{\"currency\":\"USD\",\"net_liquidation\":\"unknown\"}");
        assertThrows(IllegalStateException.class, () -> StockAccountData.summary(malformed));
    }

    @Test void realDailyNavDoesNotInventOpeningBalanceOrTwoHourLine() throws Exception {
        JsonNode data = performance("\"1D\":{\"start_nav\":0,\"frequency\":\"D\",\"dates\":[\"20261001\"],\"nav\":[250],\"cps\":[0.01]}");
        var daily = StockHistoryData.account(data, StockHistoryData.Range.D1, now);
        assertEquals("USD", daily.get("currency"));
        assertEquals("NAV", daily.get("metric"));
        assertEquals("1d", daily.get("resolution"));
        assertEquals(true, daily.get("available"));
        assertEquals(1, points(daily).size());
        assertEquals(new BigDecimal("250"), points(daily).get(0).get("value"));
        assertEquals("2026-10-01T00:00:00Z", points(daily).get(0).get("time"));
        assertTrue(daily.get("message").toString().contains("只有一个"));
        assertFalse(daily.toString().contains("PRIVATE"));
        var intraday = StockHistoryData.account(data, StockHistoryData.Range.H2, now);
        assertEquals(false, intraday.get("available"));
        assertEquals(List.of(), points(intraday));
    }

    @Test void limitedAccountLifetimeAndRealGapsRemainVisible() throws Exception {
        JsonNode data = performance("\"1Y\":{\"start_nav\":0,\"frequency\":\"D\",\"dates\":[\"20260925\",\"20260928\",\"20260929\"],\"nav\":[100,null,110],\"cps\":[0,null,0.1]}");
        var annual = StockHistoryData.account(data, StockHistoryData.Range.Y1, now);
        assertEquals(3, points(annual).size());
        assertNull(points(annual).get(1).get("value"));
        assertEquals("2026-09-25T00:00:00Z", annual.get("dataFrom"));
        assertEquals("TWR", annual.get("portfolioMeasure"));
    }

    @Test void multipleAccountsAreNotCombinedAcrossCurrencies() throws Exception {
        JsonNode data = tree("{\"accounts\":{\"one\":{\"base_currency\":\"USD\"},\"two\":{\"base_currency\":\"CAD\"}}}");
        assertEquals(false, StockHistoryData.account(data, StockHistoryData.Range.M1, now).get("available"));
    }

    @Test void malformedAndOutOfOrderAccountArraysFailWithoutReconstructingValues() throws Exception {
        JsonNode mismatched = performance("\"1M\":{\"frequency\":\"D\",\"dates\":[\"20260925\",\"20260928\"],\"nav\":[100],\"cps\":[0,0.1]}");
        assertThrows(IllegalStateException.class, () -> StockHistoryData.account(mismatched, StockHistoryData.Range.M1, now));
        JsonNode reversed = performance("\"1M\":{\"frequency\":\"D\",\"dates\":[\"20260928\",\"20260925\"],\"nav\":[100,110],\"cps\":[0,0.1]}");
        assertThrows(IllegalStateException.class, () -> StockHistoryData.account(reversed, StockHistoryData.Range.M1, now));
    }

    @Test void intradayUsesMostRecentActualTradingWindowAndPreservesMissingPrices() throws Exception {
        var data = tree("""
                {"time":["2026-10-01T13:30:00Z","2026-10-01T18:00:00Z","2026-10-01T19:00:00Z","2026-10-01T19:59:00Z"],"close":[99,100,null,102],"delayed":15}
                """);
        var history = StockHistoryData.stock(data, StockHistoryData.Range.H2, stock, now);
        assertEquals(3, points(history).size());
        assertEquals("2026-10-01T18:00:00Z", history.get("dataFrom"));
        assertNull(points(history).get(1).get("value"));
        assertEquals(15, history.get("delayed"));
        assertEquals("PRICE", history.get("metric"));
    }

    @Test void dateOnlyIntradayIsNotMistakenForMinuteResolution() throws Exception {
        var data = tree("{\"time\":[\"2026-10-01\"],\"close\":[100]}");
        assertThrows(IllegalStateException.class, () -> StockHistoryData.stock(data, StockHistoryData.Range.H2, stock, now));
        var daily = StockHistoryData.stock(data, StockHistoryData.Range.M1, stock, now);
        assertEquals(1, points(daily).size());
    }

    @Test void localSnapshotsUseWallClockWindowRatherThanRevivingOldData() throws Exception {
        JsonNode samples = tree("""
                {"currency":"USD","points":[{"time":"2026-10-01T20:00:00Z","value":100},{"time":"2026-10-02T00:30:00Z","value":101},{"time":"2026-10-02T01:30:00Z","value":102}]}
                """);
        var history = StockHistoryData.localAccount(samples, now);
        assertEquals(2, points(history).size());
        assertEquals("snapshot", history.get("resolution"));
        assertEquals("2026-10-02T00:30:00Z", history.get("dataFrom"));
        assertEquals(false, StockHistoryData.localAccount(samples, now.plusSeconds(86400)).get("available"));
    }

    @Test void fifteenDayWindowIncludesLatestDateAndOnlyFourteenPrecedingCalendarDays() throws Exception {
        JsonNode data = tree("{\"time\":[\"2026-09-16\",\"2026-09-17\",\"2026-10-01\"],\"close\":[98,99,100]}");
        var history = StockHistoryData.stock(data, StockHistoryData.Range.D15, stock, now);
        assertEquals(2, points(history).size());
        assertEquals("2026-09-17T00:00:00Z", history.get("dataFrom"));
    }

    private JsonNode performance(String periods) throws Exception {
        return tree("{\"portfolio_measure\":\"TWR\",\"currency_type\":\"base\",\"included_accounts\":[\"PRIVATE\"],\"accounts\":{\"account\":{\"base_currency\":\"USD\",\"periods\":{" + periods + "}}}}");
    }
}
