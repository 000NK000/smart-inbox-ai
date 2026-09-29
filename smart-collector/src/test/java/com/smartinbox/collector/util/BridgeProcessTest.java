package com.smartinbox.collector.util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BridgeProcessTest {
    @TempDir Path temp;
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
