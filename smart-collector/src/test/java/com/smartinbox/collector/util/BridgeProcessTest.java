package com.smartinbox.collector.util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BridgeProcessTest {
    @TempDir Path temp;
    @Test void cooperativeCancellationStopsOnlyTheOwnedReadProcessPromptly() throws Exception {
        Path source = temp.resolve("CancellableOutput.java"), started = temp.resolve("pid.txt");
        Files.writeString(source, "class CancellableOutput { public static void main(String[] args) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), Long.toString(ProcessHandle.current().pid())); Thread.sleep(30000); } }");
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var cancellation = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        long pid = -1;
        try {
            var work = executor.submit(() -> BridgeProcess.capture(new ProcessBuilder(javaBinary, source.toString(), started.toString()), temp, Duration.ofSeconds(30), cancellation::get));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(started), "Fixture process started");
            pid = Long.parseLong(Files.readString(started));
            cancellation.set(true);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null) handle.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            cancellation.set(true); executor.shutdownNow();
            if (pid > 0) ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
    @Test void drainsOutputLargerThanPipeAndCleansTemporaryFile() throws Exception {
        Path source = temp.resolve("LargeOutput.java");
        Files.writeString(source, "class LargeOutput { public static void main(String[] args) { System.out.print(\"x\".repeat(1000000)); } }");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String result = BridgeProcess.capture(new ProcessBuilder(java, source.toString()), temp, Duration.ofSeconds(20));
        assertEquals(1000000, result.length());
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
    @Test void timesOutAndCleansTemporaryFile() throws Exception {
        Path source = temp.resolve("SlowOutput.java");
        Files.writeString(source, "class SlowOutput { public static void main(String[] args) throws Exception { Thread.sleep(30000); } }");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertThrows(java.util.concurrent.TimeoutException.class, () -> BridgeProcess.capture(new ProcessBuilder(java, source.toString()), temp, Duration.ofSeconds(2)));
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
}
