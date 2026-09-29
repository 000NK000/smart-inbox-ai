package com.smartinbox.collector.task;

import com.smartinbox.collector.service.*;
import com.smartinbox.collector.service.impl.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceRetryTest {
    @Test void manualAndScheduledSyncShareGuardAndRetryOnlyRequestedChannel() throws Exception {
        var email = mock(EmailService.class); var graph = mock(OutlookGraphService.class); var desktop = mock(OutlookDesktopService.class);
        var oauth = mock(OutlookOAuthService.class); var health = new SourceHealthService();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(email.fetchSource("GMAIL")).thenAnswer(call -> {
            started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            return new MailSyncReport(true, "connected", "imap", 3, 1, 1, 2, "", true);
        });
        var task = new EmailCollectorTask(email, graph, desktop, oauth, health);
        try {
            assertTrue(task.retry("gmail")); assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(task.retry("GMAIL")); assertFalse(task.syncSource("GMAIL"));
            assertEquals(true, health.snapshots().get(0).get("inFlight"));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((boolean) health.snapshots().get(0).get("inFlight") && System.nanoTime() < deadline) Thread.sleep(10);
            var result = health.snapshots().get(0); assertEquals("connected", result.get("state"));
            assertEquals(1, result.get("published")); assertNotEquals("", result.get("lastSuccess"));
            verify(email).fetchSource("GMAIL"); verify(email, never()).fetchSource("QQMAIL"); verifyNoInteractions(graph, desktop);
            assertThrows(IllegalArgumentException.class, () -> task.retry("../../all"));
        } finally { release.countDown(); task.close(); }
    }
    @Test void failureRetainsLastSuccessButIsNotDisplayedAsHealthy() {
        var health = new SourceHealthService(); health.begin("OUTLOOK");
        health.finish("OUTLOOK", new MailSyncReport(true, "connected", "desktop", 1, 1, 1, 0, "", true));
        String success = (String) health.snapshots().get(2).get("lastSuccess");
        health.begin("OUTLOOK"); health.finish("OUTLOOK", MailSyncReport.failed("desktop", "offline"));
        var result = health.snapshots().get(2); assertEquals(success, result.get("lastSuccess"));
        assertEquals("failed", result.get("state")); assertEquals("offline", result.get("failureCode")); assertEquals(false, result.get("inFlight"));
    }
}
