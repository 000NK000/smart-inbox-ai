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
        if (!health.begin(source)) return false;
        return runReserved(source, false);
    }

    public boolean syncDesktop() {
        if (!health.begin("OUTLOOK")) return false;
        return runReserved("OUTLOOK", true);
    }

    public boolean retry(String source) {
        String normalized = SourceHealthService.source(source);
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
            if (!"OUTLOOK".equals(source)) report = emailService.fetchSource(source);
            else if (!desktopOnly && outlookOAuthService.isConnected() && outlookGraphService.fetchRecentEmails()) report = outlookGraphService.report();
            else {
                outlookDesktopService.fetchRecentEmails();
                report = outlookDesktopService.report();
            }
            if (report == null) report = MailSyncReport.failed("", "invalid_sync_result");
        } catch (Exception error) {
            logger.warn("Mail channel {} failed ({})", source, error.getClass().getSimpleName());
            report = MailSyncReport.failed("", "sync_failed");
        }
        health.finish(source, report);
        return report.success();
    }

    @PreDestroy void close() { retries.shutdownNow(); }
}
