package com.smartinbox.processor.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded, non-preemptive queue for this process's local model requests. */
@Component
public class AiRequestScheduler implements AutoCloseable {
    public enum Feature {
        NEWS_BRIEF(0, 75, 700), NEWS_TRANSLATION(0, 120, 2200), CHAT(0, 120, 1200),
        WEATHER(1, 60, 450), CHAT_RETRIEVAL(0, 60, 0), MAIL_INSIGHT(0, 180, 1900), MAIL_EMBEDDING(10, 150, 0),
        MAIL_SUMMARY(10, 150, 800), JOB_MAIL(15, 150, 1400), MAIL_TASK(20, 150, 1600), MAIL_REVIEW(20, 150, 1200);
        final int priority;
        public final int deadlineSeconds, maxTokens;
        Feature(int priority, int seconds, int tokens) { this.priority = priority; deadlineSeconds = seconds; maxTokens = tokens; }
    }
    @FunctionalInterface public interface Attempt { String call(Duration remaining) throws Exception; }
    public static final class RetryableResponse extends IOException {
        public RetryableResponse(int status) { super("Local AI temporarily unavailable (HTTP " + status + ")"); }
    }
    public static final class QueueFull extends RejectedExecutionException {
        public QueueFull() { super("Local AI queue is full; please retry later"); }
    }
    private final Object lock = new Object();
    private final PriorityQueue<Job> queue = new PriorityQueue<>(Comparator.comparingInt((Job job) -> job.feature.priority).thenComparingLong(job -> job.sequence));
    private final Map<String, Job> inFlight = new HashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int concurrency, capacity;
    private long completed, failed, rejected, deduplicated;
    private int running;
    private boolean closed;

    public AiRequestScheduler(@Value("${smart.ai.concurrency:1}") int concurrency,
                              @Value("${smart.ai.queue-capacity:32}") int capacity) {
        this.concurrency = Math.max(1, Math.min(2, concurrency));
        this.capacity = Math.max(1, Math.min(256, capacity));
        for (int index = 0; index < this.concurrency; index++) {
            Thread worker = new Thread(this::work, "local-ai-" + index);
            worker.setDaemon(true); workers.add(worker); worker.start();
        }
    }
    public String execute(Feature feature, String key, Attempt attempt) throws Exception {
        return execute(feature, key, Duration.ofSeconds(feature.deadlineSeconds), attempt);
    }
    // The budget includes queue waiting, both attempts and retry delay, not just network IO.
    public String execute(Feature feature, String key, Duration budget, Attempt attempt) throws Exception {
        Job job;
        synchronized (lock) {
            if (closed) throw new RejectedExecutionException("Local AI scheduler stopped");
            job = inFlight.get(key);
            if (job != null) { job.waiters++; deduplicated++; }
            else {
                if (queue.size() >= capacity) { rejected++; throw new QueueFull(); }
                job = new Job(feature, key, sequence.getAndIncrement(), budget, attempt);
                queue.add(job); inFlight.put(key, job); lock.notifyAll();
            }
        }
        boolean abandoned = false;
        try {
            return job.result.get(Math.max(1, job.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException error) {
            abandoned = true;
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("Local AI request failed", cause);
        } finally {
            synchronized (lock) {
                job.waiters--;
                if (abandoned && job.waiters == 0 && !job.result.isDone()) {
                    queue.remove(job); inFlight.remove(key, job);
                    if (job.result.completeExceptionally(new CancellationException("Local AI request cancelled"))) failed++;
                    if (job.worker != null) job.worker.interrupt();
                }
            }
        }
    }
    private void work() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (!closed && queue.isEmpty()) {
                    try { lock.wait(); } catch (InterruptedException ignored) { if (closed) return; }
                }
                if (closed) return;
                job = queue.remove(); job.worker = Thread.currentThread(); running++;
            }
            try {
                if (job.result.isDone()) continue;
                String result = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    long left = job.deadline - System.nanoTime();
                    if (left <= 0) throw new TimeoutException("Local AI deadline exceeded");
                    try { result = job.attempt.call(Duration.ofNanos(left)); break; }
                    catch (IOException transientFailure) {
                        // Never retry cancelled/deadline-exceeded work or malformed model output.
                        if (attempt == 1 || job.result.isDone() || Thread.currentThread().isInterrupted()
                                || job.deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(250)) throw transientFailure;
                        Thread.sleep(200);
                    }
                }
                if (System.nanoTime() >= job.deadline) throw new TimeoutException("Local AI deadline exceeded");
                synchronized (lock) { if (job.result.complete(result)) completed++; }
            } catch (Exception error) {
                synchronized (lock) { if (job.result.completeExceptionally(error)) failed++; }
            } finally {
                synchronized (lock) { running--; job.worker = null; inFlight.remove(job.key, job); }
                Thread.interrupted(); // Cancellation must not poison the next queued job.
            }
        }
    }
    public Status status() {
        synchronized (lock) {
            Map<String, Integer> features = new TreeMap<>();
            inFlight.values().forEach(job -> features.merge(job.feature.name(), 1, Integer::sum));
            return new Status(concurrency, capacity, running, queue.size(), deduplicated, completed, failed, rejected, Map.copyOf(features));
        }
    }
    public record Status(int concurrency, int capacity, int running, int queued, long deduplicated,
                         long completed, long failed, long rejected, Map<String, Integer> features) { }
    @Override @PreDestroy public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            inFlight.values().forEach(job -> job.result.completeExceptionally(new CancellationException("Local AI scheduler stopped")));
            queue.clear(); inFlight.clear(); lock.notifyAll();
        }
        workers.forEach(Thread::interrupt);
    }
    private static final class Job {
        final Feature feature; final String key; final long sequence, deadline; final Attempt attempt;
        final CompletableFuture<String> result = new CompletableFuture<>();
        int waiters = 1; Thread worker;
        Job(Feature feature, String key, long sequence, Duration budget, Attempt attempt) {
            this.feature = feature; this.key = key; this.sequence = sequence;
            deadline = System.nanoTime() + Math.max(1, budget.toNanos()); this.attempt = attempt;
        }
    }
}
