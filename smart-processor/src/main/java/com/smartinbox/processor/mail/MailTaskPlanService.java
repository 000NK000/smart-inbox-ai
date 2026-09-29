package com.smartinbox.processor.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.repository.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Materialized progress: browser polls never load or hash mail body CLOBs. */
@Service
public class MailTaskPlanService {
    private static final String RECONCILE_ERROR = "暂时无法读取任务规划进度，请稍后重试。";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MailTaskPlanService.class);
    private final MailSummaryRepository mails;
    private final MailTaskAnalysisRepository analyses;
    private final MailTaskAnalyzer analyzer;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "mail-task-plan"));
    private final ScheduledExecutorService reconciler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "mail-task-progress"));
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Long> failed = new HashSet<>();
    private List<MailEntry> entries = List.of();
    private final Map<Long, Outcome> outcomes = new HashMap<>();
    private Revision revision;
    private boolean initialized;
    private Instant lastFinished;
    private String error = "";
    private volatile Overview snapshot = new Overview("PENDING", MailWindow.HOURS, 0, 0, 0, List.of(), List.of(), null, null, "", System.currentTimeMillis());

    public MailTaskPlanService(MailSummaryRepository mails, MailTaskAnalysisRepository analyses, MailTaskAnalyzer analyzer, ObjectMapper mapper) {
        this.mails = mails; this.analyses = analyses; this.analyzer = analyzer; this.mapper = mapper;
    }
    private static Thread daemon(Runnable action, String name) { Thread t = new Thread(action, name); t.setDaemon(true); return t; }

    @PostConstruct public void start() {
        // Rebuild once after restart, then only when cheap metadata or the 120-hour window changes.
        reconciler.scheduleWithFixedDelay(() -> {
            try { reconcile(false); }
            catch (RuntimeException problem) {
                synchronized (lock) { error = RECONCILE_ERROR; publish(); }
                log.warn("Task-plan progress reconciliation failed ({})", problem.getClass().getSimpleName());
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    void reconcile(boolean force) {
        synchronized (lock) {
            var value = mails.taskPlanRevision(MailWindow.cutoff(), MailWindow.SOURCES);
            Revision next = value == null ? null : new Revision(value.getMailCount(), value.getLastChanged());
            if (!force && initialized && Objects.equals(revision, next)) {
                if (RECONCILE_ERROR.equals(error)) { error = ""; publish(); }
                return;
            }
            var recent = mails.findTaskPlanMails(MailWindow.cutoff(), MailWindow.SOURCES);
            // Keep metadata and fingerprints, not all email bodies, in the progress snapshot.
            entries = recent.stream().map(mail -> new MailEntry(mail.getId(), MailTaskAnalyzer.subject(mail), mail.getSource(),
                    mail.getCreatedTime(), MailTaskAnalyzer.fingerprint(mail))).toList();
            outcomes.clear();
            analyses.findAllById(entries.stream().map(MailEntry::id).toList()).forEach(row -> {
                try {
                    List<MailTaskAnalyzer.Suggestion> tasks = mapper.readValue(row.getTasksJson(), new TypeReference<>() { });
                    if (tasks != null && tasks.stream().allMatch(task -> task != null && task.priority() != null))
                        outcomes.put(row.getMailId(), new Outcome(row.getFingerprint(), List.copyOf(tasks), row.getAnalyzedAt()));
                } catch (Exception invalid) { /* Invalid persisted JSON is treated as pending and can be retried. */ }
            });
            failed.retainAll(entries.stream().map(MailEntry::id).toList());
            revision = next; initialized = true;
            if (RECONCILE_ERROR.equals(error)) error = "";
            publish();
        }
    }

    @TransactionalEventListener
    public void onReadStateChanged(MailReadStateChanged event) {
        // AFTER_COMMIT: a failed/rolled-back read request must not hide suggestions.
        synchronized (lock) {
            revision = null;
            if (event.read()) {
                entries = entries.stream().filter(entry -> !entry.id().equals(event.mailId())).toList();
                outcomes.remove(event.mailId());
                failed.remove(event.mailId());
                publish();
            }
        }
        // Unread/snooze restores eligibility; reconcile on the next periodic pass.
        // Persisted analyses and confirmed tasks deliberately remain intact.
    }

    public void refresh() {
        if (!running.compareAndSet(false, true)) return;
        synchronized (lock) { failed.clear(); error = ""; publish(); }
        worker.submit(() -> {
            try {
                reconcile(true);
                List<MailEntry> batch;
                synchronized (lock) { batch = List.copyOf(entries); }
                int consecutiveFailures = 0;
                for (MailEntry entry : batch) {
                    if (Thread.currentThread().isInterrupted()) break;
                    synchronized (lock) { if (valid(entry)) continue; }
                    try {
                        var mail = mails.findById(entry.id()).orElse(null);
                        if (mail == null || mail.isInboxRead() || mail.getCreatedTime() == null || mail.getCreatedTime().isBefore(MailWindow.cutoff())) continue;
                        String fingerprint = MailTaskAnalyzer.fingerprint(mail);
                        var tasks = analyzer.analyze(mail);
                        var saved = new MailTaskAnalysis();
                        saved.setMailId(mail.getId()); saved.setFingerprint(fingerprint);
                        saved.setTasksJson(mapper.writeValueAsString(tasks)); saved.setAnalyzedAt(Instant.now());
                        analyses.save(saved);
                        synchronized (lock) {
                            outcomes.put(mail.getId(), new Outcome(fingerprint, List.copyOf(tasks), saved.getAnalyzedAt()));
                            failed.remove(mail.getId()); publish();
                        }
                        consecutiveFailures = 0;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt(); break;
                    } catch (Exception exception) {
                        synchronized (lock) { failed.add(entry.id()); publish(); }
                        log.warn("Mail task analysis failed for id={} ({})", entry.id(), exception.getClass().getSimpleName());
                        if (++consecutiveFailures >= 3) {
                            synchronized (lock) { error = "连续分析失败，已暂停本轮分析。请确认本地 AI 可用后重试，已完成的结果会保留。"; }
                            break;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                synchronized (lock) { error = "暂时无法读取邮件或保存分析结果，请稍后重试。"; }
            } finally {
                synchronized (lock) { lastFinished = Instant.now(); running.set(false); publish(); }
            }
        });
    }
    public Progress retryFailed() { refresh(); return progress(); }
    private boolean valid(MailEntry entry) {
        Outcome outcome = outcomes.get(entry.id());
        return outcome != null && entry.fingerprint().equals(outcome.fingerprint());
    }
    private void publish() {
        List<Task> tasks = new ArrayList<>(); List<MailFailure> failures = new ArrayList<>();
        int analyzed = 0; Instant updated = null;
        for (var entry : entries) {
            if (valid(entry)) {
                analyzed++; Outcome row = outcomes.get(entry.id());
                if (row.analyzedAt() != null && (updated == null || row.analyzedAt().isAfter(updated))) updated = row.analyzedAt();
                for (var task : row.tasks()) tasks.add(new Task(task, entry.id(), entry.subject(), entry.source(), entry.receivedAt()));
            } else if (failed.contains(entry.id())) failures.add(new MailFailure(entry.id(), entry.subject(), entry.source()));
        }
        tasks.sort(Comparator.comparingInt((Task task) -> switch (task.suggestion().priority()) { case "HIGH" -> 0; case "LOW" -> 2; default -> 1; })
                .thenComparing(Task::receivedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        String state = running.get() ? "RUNNING" : !error.isEmpty() || !failures.isEmpty() ? "PARTIAL"
                : initialized && analyzed == entries.size() ? "COMPLETE" : "PENDING";
        Overview next = new Overview(state, MailWindow.HOURS, entries.size(), analyzed, entries.size() - analyzed, List.copyOf(failures),
                List.copyOf(tasks), updated, lastFinished, error, snapshot.version());
        if (!next.equals(snapshot)) snapshot = new Overview(next.state(), next.windowHours(), next.totalMails(), next.analyzedMails(), next.pendingMails(),
                next.failedMails(), next.tasks(), next.updatedAt(), next.lastFinished(), next.error(), next.version() + 1);
    }
    public Overview overview() { return snapshot; }
    public Progress progress() {
        Overview view = snapshot;
        return new Progress(view.version(), view.state(), view.windowHours(), view.totalMails(), view.analyzedMails(), view.pendingMails(),
                view.failedMails().size(), view.tasks().size(), view.lastFinished(), view.error(), "RUNNING".equals(view.state()));
    }
    @PreDestroy public void close() { reconciler.shutdownNow(); worker.shutdownNow(); }
    private record Revision(long count, LocalDateTime changed) { }
    private record MailEntry(Long id, String subject, String source, LocalDateTime receivedAt, String fingerprint) { }
    private record Outcome(String fingerprint, List<MailTaskAnalyzer.Suggestion> tasks, Instant analyzedAt) { }
    public record Task(MailTaskAnalyzer.Suggestion suggestion, Long mailId, String subject, String source, LocalDateTime receivedAt) { }
    public record MailFailure(Long mailId, String subject, String source) { }
    public record Progress(long version, String state, int windowHours, int totalMails, int analyzedMails, int pendingMails,
                           int failedCount, int taskCount, Instant lastFinished, String error, boolean running) { }
    public record Overview(String state, int windowHours, int totalMails, int analyzedMails, int pendingMails,
                           List<MailFailure> failedMails, List<Task> tasks, Instant updatedAt, Instant lastFinished, String error, long version) { }
}
