package com.smartinbox.processor.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local file signal: no network shutdown endpoint and no source-mail mutations. */
@Component
public class LocalRuntimeShutdown {
    private final ConfigurableApplicationContext context;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "runtime-standby"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Path signal = Path.of(".smart-inbox", "runtime", "stop-processor");
    public LocalRuntimeShutdown(ConfigurableApplicationContext context) { this.context = context; }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { monitor.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS); }
    private void check() {
        try {
            if (!Files.isRegularFile(signal) || Files.size(signal) > 32) return;
            if (!Files.readString(signal).trim().equals(Long.toString(ProcessHandle.current().pid()))) return;
            if (!closing.compareAndSet(false, true)) return;
            Files.deleteIfExists(signal);
            new Thread(() -> {
                context.close(); // Flush H2 and close MQ clients before releasing the process.
                System.exit(0);
            }, "runtime-graceful-stop").start();
        } catch (java.io.IOException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not read local standby signal");
        }
    }
    @PreDestroy public void close() { monitor.shutdownNow(); }
}