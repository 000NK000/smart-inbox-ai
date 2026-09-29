package com.smartinbox.processor.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static com.smartinbox.processor.service.AiRequestScheduler.Feature.*;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestSchedulerTest {
    static void until(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(ready.getAsBoolean());
    }
    @Test void boundsQueuePrioritizesForegroundAndDeduplicatesIdenticalWork() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(6);
        CountDownLatch release = new CountDownLatch(1);
        try (var scheduler = new AiRequestScheduler(1, 2)) {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            var active = callers.submit(() -> scheduler.execute(MAIL_TASK, "active", left -> { release.await(); return "active"; }));
            until(() -> scheduler.status().running() == 1);
            var background = callers.submit(() -> scheduler.execute(MAIL_TASK, "background", left -> { order.add("background"); return "B"; }));
            until(() -> scheduler.status().queued() == 1);
            var foreground = callers.submit(() -> scheduler.execute(NEWS_BRIEF, "foreground", left -> { order.add("foreground"); return "F"; }));
            until(() -> scheduler.status().queued() == 2);
            var duplicate = callers.submit(() -> scheduler.execute(NEWS_BRIEF, "foreground", left -> { fail("Duplicate inference"); return "bad"; }));
            until(() -> scheduler.status().deduplicated() == 1);
            assertThrows(AiRequestScheduler.QueueFull.class, () -> scheduler.execute(CHAT, "overflow", left -> "no"));
            release.countDown();
            assertEquals("active", active.get(2, TimeUnit.SECONDS));
            assertEquals("F", foreground.get(2, TimeUnit.SECONDS)); assertEquals("F", duplicate.get(2, TimeUnit.SECONDS));
            assertEquals("B", background.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("foreground", "background"), order);
            assertEquals(1, scheduler.status().rejected());
        } finally { release.countDown(); callers.shutdownNow(); }
    }
    @Test void retriesOnlyTransientFailuresAndRespectsConfiguredConcurrency() throws Exception {
        try (var scheduler = new AiRequestScheduler(99, 4)) {
            assertEquals(2, scheduler.status().concurrency());
            var calls = new AtomicInteger();
            assertEquals("ok", scheduler.execute(WEATHER, "retry", left -> {
                if (calls.incrementAndGet() == 1) throw new IOException("temporary"); return "ok";
            }));
            assertEquals(2, calls.get()); calls.set(0);
            assertThrows(IllegalStateException.class, () -> scheduler.execute(WEATHER, "invalid", left -> {
                calls.incrementAndGet(); throw new IllegalStateException("invalid semantic response");
            }));
            assertEquals(1, calls.get());
        }
    }
    @Test void deadlineIncludesQueueTimeAndCancelledJobNeverRuns() throws Exception {
        ExecutorService callers = Executors.newSingleThreadExecutor(); CountDownLatch release = new CountDownLatch(1);
        try (var scheduler = new AiRequestScheduler(1, 3)) {
            var active = callers.submit(() -> scheduler.execute(MAIL_TASK, "slow", left -> { release.await(); return "ok"; }));
            until(() -> scheduler.status().running() == 1);
            assertThrows(TimeoutException.class, () -> scheduler.execute(CHAT, "expired", Duration.ofMillis(40), left -> {
                fail("Expired queued work ran"); return "bad";
            }));
            assertEquals(0, scheduler.status().queued());
            release.countDown(); active.get(2, TimeUnit.SECONDS);
            assertEquals("new", scheduler.execute(CHAT, "expired", left -> "new"));
        } finally { release.countDown(); callers.shutdownNow(); }
    }
    @Test void deadlineInterruptsActiveInferenceAndReleasesSlot() throws Exception {
        try (var scheduler = new AiRequestScheduler(1, 3)) {
            var interrupted = new CountDownLatch(1);
            assertThrows(TimeoutException.class, () -> scheduler.execute(CHAT, "active-timeout", Duration.ofMillis(80), left -> {
                try { Thread.sleep(2000); return "late"; }
                catch (InterruptedException error) { interrupted.countDown(); throw error; }
            }));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals("next", scheduler.execute(CHAT, "next", left -> "next"));
        }
    }
    @Test void concurrencyTwoRunsOnlyTwoRequestsAndKeepsThirdQueued() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(3); CountDownLatch release = new CountDownLatch(1);
        try (var scheduler = new AiRequestScheduler(2, 4)) {
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
            List<Future<String>> requests = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String key = "request-" + i;
                requests.add(callers.submit(() -> scheduler.execute(CHAT, key, left -> {
                    int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
                    try { release.await(); return key; } finally { active.decrementAndGet(); }
                })));
            }
            until(() -> scheduler.status().running() == 2 && scheduler.status().queued() == 1);
            assertEquals(2, peak.get()); release.countDown();
            for (var request : requests) request.get(2, TimeUnit.SECONDS);
            assertEquals(2, peak.get());
        } finally { release.countDown(); callers.shutdownNow(); }
    }
}
