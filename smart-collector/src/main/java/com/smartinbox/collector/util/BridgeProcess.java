package com.smartinbox.collector.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

/** Drain stdout concurrently so a full pipe cannot deadlock the bridge. */
public final class BridgeProcess {
    public static String capture(ProcessBuilder builder, Path directory, Duration timeout) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
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
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new TimeoutException("Outlook bridge timed out");
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
