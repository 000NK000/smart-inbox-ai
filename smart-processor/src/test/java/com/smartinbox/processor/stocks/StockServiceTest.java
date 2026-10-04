package com.smartinbox.processor.stocks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.stocks.gpt.StockGptService;
import com.smartinbox.processor.stocks.ibkr.StockIbkrService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockServiceTest {
    StockWatchRepository watches=mock(StockWatchRepository.class);
    StockReportRepository reports=mock(StockReportRepository.class);
    StockIbkrService ibkr=mock(StockIbkrService.class);
    StockGptService gpt=mock(StockGptService.class);
    StockService service=new StockService(watches,reports,ibkr,gpt,new ObjectMapper());
    @BeforeEach void setup() {
        when(ibkr.status()).thenReturn(Map.of("connected",true));
        when(watches.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        when(reports.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
    }
    @Test void watchlistValidatesDuplicateSymbolsAndOptimisticVersions() {
        var created=service.create(new StockService.WatchRequest("abc","nasdaq","Example","My note",null));
        assertEquals("ABC",created.symbol);assertEquals("NASDAQ",created.exchange);
        created.version=2L;when(watches.findById(created.id)).thenReturn(Optional.of(created));
        assertThrows(ResponseStatusException.class,()->service.update(created.id,new StockService.WatchRequest("ABC","NASDAQ","Name","",1L)));
        assertThrows(ResponseStatusException.class,()->service.delete(created.id,1L));
        when(watches.findBySymbolAndExchange("ABC","NASDAQ")).thenReturn(Optional.of(created));
        assertThrows(ResponseStatusException.class,()->service.create(new StockService.WatchRequest("abc","nasdaq","Name","",null)));
        assertThrows(ResponseStatusException.class,()->service.create(new StockService.WatchRequest("<script>","","","",null)));
        verify(watches,never()).delete(any());
    }
    private Map<String,Object> snapshot() {
        return Map.of("positions",List.of(Map.of("symbol","AAA","currency","USD","marketValue",100,"accountId","DO-NOT-SEND")),"totals",List.of(Map.of("currency","USD","marketValue",100)),"asOf",12345L);
    }
    @Test void successfulSnapshotIsCachedAndFailureKeepsLabelledLastKnownData() {
        when(ibkr.fetchPortfolio()).thenReturn(snapshot()).thenThrow(new IllegalStateException("Unavailable"));
        assertEquals(12345L,service.portfolio(false).get("asOf"));service.portfolio(false);verify(ibkr,times(1)).fetchPortfolio();
        var stale=service.portfolio(true);assertEquals(true,stale.get("stale"));assertEquals(12345L,stale.get("asOf"));
        assertThrows(ResponseStatusException.class,()->service.analyze(new StockService.AnalysisRequest("PORTFOLIO",null,"sample-model")));
        verifyNoInteractions(gpt);verify(reports,never()).saveAndFlush(any());
    }
    @Test void disconnectClearsOldPortfolioRatherThanLeakingDifferentAccountsSnapshot() {
        when(ibkr.fetchPortfolio()).thenReturn(snapshot());service.portfolio(false);
        when(ibkr.status()).thenReturn(Map.of("connected",false));
        assertEquals(List.of(),service.portfolio(false).get("positions"));
        when(ibkr.status()).thenReturn(Map.of("connected",true));when(ibkr.fetchPortfolio()).thenThrow(new IllegalStateException("Offline"));
        assertThrows(IllegalStateException.class,()->service.portfolio(false));
    }
    @Test void reportsOnlyPersistCompletedOutputAndStripPersonalAccountId() {
        when(ibkr.fetchPortfolio()).thenReturn(snapshot());when(gpt.analyze(anyString(),anyString())).thenReturn("中文报告");
        var report=service.analyze(new StockService.AnalysisRequest("PORTFOLIO",null,"sample-model"));assertEquals("中文报告",report.body);assertEquals(12345L,report.dataAsOf);
        var prompt=ArgumentCaptor.forClass(String.class);verify(gpt).analyze(eq("sample-model"),prompt.capture());assertFalse(prompt.getValue().contains("DO-NOT-SEND"));assertTrue(prompt.getValue().contains("不同币种"));
        reset(reports);when(gpt.analyze(anyString(),anyString())).thenThrow(new IllegalStateException("Interrupted"));
        assertThrows(IllegalStateException.class,()->service.analyze(new StockService.AnalysisRequest("PORTFOLIO",null,"sample-model")));
        verify(reports,never()).saveAndFlush(any());
    }
    @Test void chartsOnlyReadContractsInTheCurrentHoldingsAndValidateScope() {
        Map<String, Object> held = Map.of("contractId", 123L, "symbol", "EXAMPLE", "currency", "USD", "assetClass", "STK");
        when(ibkr.fetchPortfolio()).thenReturn(Map.of("positions", List.of(held), "totals", List.of()));
        when(ibkr.fetchHistory("STOCK", "15D", held, false)).thenReturn(Map.of("available", true));
        assertEquals(true, service.history("STOCK", "15D", 123L, false).get("available"));
        assertThrows(ResponseStatusException.class, () -> service.history("STOCK", "15D", 999L, false));
        assertThrows(ResponseStatusException.class, () -> service.history("STOCK", "ALL", 123L, false));
        assertThrows(ResponseStatusException.class, () -> service.history("PORTFOLIO", "1M", 123L, false));
        verify(ibkr, times(1)).fetchHistory(anyString(), anyString(), anyMap(), anyBoolean());
        when(ibkr.status()).thenReturn(Map.of("connected", false));
        assertThrows(ResponseStatusException.class, () -> service.history("PORTFOLIO", "1M", null, false));
        verifyNoInteractions(gpt);
    }
}
