package com.smartinbox.collector.task;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CollectorSchedulerShutdownTest {
    @Test
    void contextCloseSignalsAnActiveReadToFinishWithoutInterruptingItsThread() throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(com.smartinbox.collector.runtime.CollectorShutdownSignal.class);
        context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class,
                () -> new EmailCollectorTask(null, null, null, null, null).taskScheduler());
        context.refresh();
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        var signal = context.getBean(com.smartinbox.collector.runtime.CollectorShutdownSignal.class);
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        scheduler.execute(() -> {
            entered.countDown();
            while (!signal.isStopping()) {
                try { Thread.sleep(10); }
                catch (InterruptedException error) { interrupted.set(true); break; }
            }
        });
        var closer = Executors.newSingleThreadExecutor();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            closer.submit(context::close).get(2, TimeUnit.SECONDS);
            assertTrue(signal.isStopping());
            assertFalse(interrupted.get());
        } finally {
            signal.stop(); scheduler.shutdown(); closer.shutdownNow(); context.close();
        }
    }
    @Test
    void farFutureScheduledWorkDoesNotHoldTheLifecycleStopPhase() throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class,
                () -> new EmailCollectorTask(null, null, null, null, null).taskScheduler());
        context.refresh();
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        var executed = new AtomicBoolean();
        var future = scheduler.schedule(() -> executed.set(true), Instant.now().plusSeconds(600));
        var periodic = scheduler.scheduleWithFixedDelay(() -> executed.set(true),
                Instant.now().plusSeconds(600), Duration.ofMinutes(2));
        var closer = Executors.newSingleThreadExecutor();
        try {
            closer.submit(context::close).get(2, TimeUnit.SECONDS);
            assertFalse(executed.get());
            assertTrue(future.isCancelled());
            assertTrue(periodic.isCancelled());
        } finally {
            scheduler.shutdown();
            closer.shutdownNow();
            context.close();
        }
    }

    @Test
    void lifecycleStopWaitsForActuallyRunningWork() throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class,
                () -> new EmailCollectorTask(null, null, null, null, null).taskScheduler());
        context.refresh();
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        scheduler.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var closer = Executors.newSingleThreadExecutor();
        try {
            var closing = closer.submit(context::close);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            scheduler.shutdown();
            closer.shutdownNow();
            context.close();
        }
    }
}
