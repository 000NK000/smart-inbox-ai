package com.smartinbox.processor.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.repository.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailTaskPlanServiceTest {
    final MailSummaryRepository mails = mock(MailSummaryRepository.class);
    final MailTaskAnalysisRepository analyses = mock(MailTaskAnalysisRepository.class);
    final MailTaskAnalyzer analyzer = mock(MailTaskAnalyzer.class);
    final Map<Long, MailTaskAnalysis> saved = new ConcurrentHashMap<>();
    MailTaskPlanService service;
    MailSummary mail;
    @BeforeEach void setup() {
        mail = MailTaskAnalyzerTest.mail("Submit assignment."); mail.setInboxRead(false); mail.setCategory("Ad");
        when(mails.findTaskPlanMails(any(), any())).thenAnswer(call -> mail.isInboxRead() ? List.of() : List.of(mail));
        when(mails.findById(mail.getId())).thenReturn(Optional.of(mail));
        when(analyses.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(analyses.findAllById(any())).thenAnswer(call -> new ArrayList<>(saved.values()));
        when(analyses.save(any())).thenAnswer(call -> { MailTaskAnalysis row=call.getArgument(0); saved.put(row.getMailId(), row); return row; });
        service = new MailTaskPlanService(mails, analyses, analyzer, new ObjectMapper());
    }
    @AfterEach void cleanup() { service.close(); }
    void finish() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.overview().state().equals("RUNNING") && System.nanoTime() < end) Thread.sleep(10);
        assertNotEquals("RUNNING", service.overview().state());
    }
    @Test void persistsResultsAndReusesThemAfterServiceRestart() throws Exception {
        when(analyzer.analyze(mail)).thenReturn(List.of(new MailTaskAnalyzer.Suggestion("1", "提交作业", "提交课程作业", "HIGH", "REQUIRED", "", "Submit assignment.")));
        service.refresh(); finish();
        assertEquals("COMPLETE", service.overview().state()); assertEquals(1, service.overview().tasks().size());
        assertEquals(120, service.overview().windowHours()); assertFalse(mail.isInboxRead());
        service.close(); service = new MailTaskPlanService(mails, analyses, analyzer, new ObjectMapper());
        service.refresh(); finish(); verify(analyzer, times(1)).analyze(mail);
        mail.setContent("Updated task body");
        service.reconcile(true);
        assertEquals("PENDING", service.overview().state()); assertTrue(service.overview().tasks().isEmpty());
        service.refresh(); finish(); verify(analyzer, times(2)).analyze(mail);
        verify(mails, never()).save(any());
    }
    @Test void failuresAreVisibleRetryableAndEmptyTaskResultsAreCached() throws Exception {
        when(analyzer.analyze(mail)).thenThrow(new IllegalStateException()).thenReturn(List.of());
        service.refresh(); finish();
        assertEquals("PARTIAL", service.overview().state()); assertEquals(1, service.overview().failedMails().size());
        assertEquals(0, service.overview().analyzedMails());
        service.refresh(); finish();
        assertEquals("COMPLETE", service.overview().state()); assertTrue(service.overview().tasks().isEmpty());
        assertEquals(1, service.overview().analyzedMails()); assertTrue(service.overview().failedMails().isEmpty());
        service.refresh(); finish(); verify(analyzer, times(2)).analyze(mail);
    }
    @Test void simultaneousRefreshDoesNotDuplicateAiWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(analyzer.analyze(mail)).thenAnswer(call -> { entered.countDown(); release.await(3, TimeUnit.SECONDS); return List.of(); });
        service.refresh(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        try { service.refresh(); assertEquals("RUNNING", service.overview().state()); }
        finally { release.countDown(); }
        finish(); verify(analyzer, times(1)).analyze(mail);
    }
    @Test void progressPollsAndUnchangedReconciliationDoNotLoadBodiesOrAnalysisClobs() throws Exception {
        when(analyzer.analyze(mail)).thenReturn(List.of());
        service.refresh(); finish();
        long version = service.progress().version();
        clearInvocations(mails, analyses);
        for (int i = 0; i < 100; i++) { assertEquals(version, service.progress().version()); service.overview(); }
        verifyNoInteractions(mails, analyses);
        service.reconcile(false);
        verify(mails).taskPlanRevision(any(), any());
        verify(mails, never()).findTaskPlanMails(any(), any());
        verifyNoInteractions(analyses);
        assertEquals(version, service.progress().version());
    }
    @Test void metadataChangeInvalidatesSnapshotForNewAndChangedMail() throws Exception {
        var revision = mock(MailSummaryRepository.TaskPlanRevision.class);
        when(revision.getMailCount()).thenReturn(1L);
        when(revision.getLastChanged()).thenReturn(java.time.LocalDateTime.of(2026, 9, 20, 12, 0));
        when(mails.taskPlanRevision(any(), any())).thenReturn(revision);
        when(analyzer.analyze(mail)).thenReturn(List.of());
        service.refresh(); finish(); long version = service.progress().version();
        mail.setContent("Changed action");
        when(revision.getLastChanged()).thenReturn(java.time.LocalDateTime.of(2026, 9, 20, 12, 1));
        service.reconcile(false);
        assertEquals("PENDING", service.progress().state());
        assertTrue(service.progress().version() > version);
        assertEquals(1, service.progress().pendingMails());
    }

    @Test void localReadImmediatelyHidesAllSuggestionsAndStaysHiddenAfterRestart() throws Exception {
        var suggestion = new MailTaskAnalyzer.Suggestion("1", "提交作业", "提交课程作业", "HIGH", "REQUIRED", "", "Submit assignment.");
        when(analyzer.analyze(mail)).thenReturn(List.of(suggestion, suggestion));
        service.refresh(); finish();
        assertEquals(2, service.overview().tasks().size());
        long version = service.progress().version();
        mail.setInboxRead(true);
        service.onReadStateChanged(new MailReadStateChanged(mail.getId(), true));
        assertTrue(service.overview().tasks().isEmpty());
        assertEquals(0, service.progress().totalMails());
        assertTrue(service.progress().version() > version);
        assertEquals(1, saved.size(), "Keep analysis cache for confirmed task references");
        service.close(); service = new MailTaskPlanService(mails, analyses, analyzer, new ObjectMapper());
        service.refresh(); finish();
        assertTrue(service.overview().tasks().isEmpty());
        verify(analyzer, times(1)).analyze(mail);
    }

    @Test void lateAiResponseCannotRestoreSuggestionsAfterLocalRead() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(analyzer.analyze(mail)).thenAnswer(call -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS);
            return List.of(new MailTaskAnalyzer.Suggestion("1", "提交作业", "提交课程作业", "HIGH", "REQUIRED", "", "Submit assignment."));
        });
        service.refresh(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            mail.setInboxRead(true);
            service.onReadStateChanged(new MailReadStateChanged(mail.getId(), true));
            assertEquals(0, service.progress().totalMails());
        } finally { release.countDown(); }
        finish();
        assertTrue(service.overview().tasks().isEmpty());
        service.reconcile(true);
        assertTrue(service.overview().tasks().isEmpty());
    }
}
