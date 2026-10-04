package com.smartinbox.collector.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Drain stdout concurrently so a full pipe cannot deadlock the bridge. */
public final class BridgeProcess {
    public static String capture(ProcessBuilder builder, Path directory, Duration timeout) throws Exception {
        return capture(builder, directory, timeout, () -> false);
    }
    public static String capture(ProcessBuilder builder, Path directory, Duration timeout, BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new CancellationException("collector_stopping");
        Process process = builder.redirectErrorStream(true).start();
        long deadline = System.nanoTime() + timeout.toNanos();
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "outlook-output"); thread.setDaemon(true); return thread;
        });
        Future<String> output = reader.submit(() -> {
            try (var stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(25_000_001);
                if (bytes.length > 25_000_000) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Outlook response exceeds 25 MB");
                }
                return new String(bytes, StandardCharsets.UTF_8).trim();
            }
        });
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new CancellationException("collector_stopping");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Outlook bridge timed out");
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS)) break;
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Outlook bridge exit " + process.exitValue());
            return output.get(5, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            output.cancel(true);
            reader.shutdownNow();
        }
    }
}
