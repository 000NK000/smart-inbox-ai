package com.smartinbox.collector.task;

import com.smartinbox.collector.service.EmailService;
import com.smartinbox.collector.service.MailSyncReport;
import com.smartinbox.collector.service.SourceHealthService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import com.smartinbox.collector.service.impl.OutlookGraphService;
import com.smartinbox.collector.service.impl.OutlookDesktopService;
import com.smartinbox.collector.service.impl.OutlookOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class EmailCollectorTask {

    private static final Logger logger = LoggerFactory.getLogger(EmailCollectorTask.class);

    private final EmailService emailService;
    private final OutlookGraphService outlookGraphService;
    private final OutlookDesktopService outlookDesktopService;
    private final OutlookOAuthService outlookOAuthService;
    private final SourceHealthService health;
    @org.springframework.beans.factory.annotation.Autowired
    private com.smartinbox.collector.runtime.CollectorShutdownSignal shutdown = new com.smartinbox.collector.runtime.CollectorShutdownSignal();
    private final ExecutorService retries = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "mail-source-retry"); thread.setDaemon(true); return thread;
    });

    public EmailCollectorTask(EmailService emailService, OutlookGraphService outlookGraphService,
            OutlookDesktopService outlookDesktopService, OutlookOAuthService outlookOAuthService, SourceHealthService health) {
        this.emailService = emailService;
        this.outlookGraphService = outlookGraphService;
        this.outlookDesktopService = outlookDesktopService;
        this.outlookOAuthService = outlookOAuthService;
        this.health = health;
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("mail-sync-");
        return scheduler;
    }

    @Scheduled(fixedDelay = 120000, initialDelay = 5000)
    public void collectEmails() {
        syncSource("GMAIL");
        syncSource("QQMAIL");
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 3000)
    public void collectOutlook() {
        syncSource("OUTLOOK");
    }

    public boolean syncSource(String source) {
        source = SourceHealthService.source(source);
        if (shutdown.isStopping()) return false;
        if (!health.begin(source)) return false;
        return runReserved(source, false);
    }

    public boolean syncDesktop() {
        if (shutdown.isStopping()) return false;
        if (!health.begin("OUTLOOK")) return false;
        return runReserved("OUTLOOK", true);
    }

    public boolean retry(String source) {
        String normalized = SourceHealthService.source(source);
        if (shutdown.isStopping()) return false;
        if (!health.begin(normalized)) return false;
        try {
            retries.execute(() -> runReserved(normalized, false));
            return true;
        } catch (RejectedExecutionException error) {
            health.finish(normalized, MailSyncReport.failed("", "collector_stopping"));
            return false;
        }
    }

    private boolean runReserved(String source, boolean desktopOnly) {
        MailSyncReport report;
        try {
            shutdown.check();
            if (!"OUTLOOK".equals(source)) report = emailService.fetchSource(source);
            else if (!desktopOnly && outlookOAuthService.isConnected() && outlookGraphService.fetchRecentEmails()) report = outlookGraphService.report();
            else {
                shutdown.check(); // A cancelled Graph read must not start a desktop fallback.
                outlookDesktopService.fetchRecentEmails();
                report = outlookDesktopService.report();
            }
            if (report == null) report = MailSyncReport.failed("", "invalid_sync_result");
        } catch (CancellationException error) {
            report = MailSyncReport.failed("", "collector_stopping");
        } catch (Exception error) {
            logger.warn("Mail channel {} failed ({})", source, error.getClass().getSimpleName());
            report = MailSyncReport.failed("", "sync_failed");
        }
        health.finish(source, report);
        return report.success();
    }

    @PreDestroy void close() {
        shutdown.stop();
        retries.shutdown();
        // A manual retry is not owned by Spring's scheduler. Let its current
        // send/checkpoint finish; source reads observe the same shutdown flag.
        // The desktop controller reports a timeout instead of killing this JVM.
        boolean interrupted = false;
        while (!retries.isTerminated()) {
            try { retries.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
