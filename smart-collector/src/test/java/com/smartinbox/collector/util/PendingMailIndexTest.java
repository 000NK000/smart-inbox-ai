package com.smartinbox.collector.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingMailIndexTest {
    @TempDir Path directory;
    @Test void legacyMarkerCannotHideMissingDatabaseRowsAndPendingExpiresAfterRestart() throws Exception {
        Path file = directory.resolve("processed.txt"); Files.writeString(file, "html-v1:lost\n");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var first = new PendingMailIndex(file, Clock.fixed(now, ZoneOffset.UTC));
        assertFalse(first.recentlyPublished("lost"));
        first.published("new");
        assertTrue(new PendingMailIndex(file, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)).recentlyPublished("new"));
        assertFalse(new PendingMailIndex(file, Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC)).recentlyPublished("new"));
    }
    @Test void brokenCheckpointRetriesRatherThanTreatingEverythingAsDone() throws Exception {
        Path file = directory.resolve("processed.txt"); Files.writeString(directory.resolve("processed.txt.pending.json"), "broken");
        var index = new PendingMailIndex(file); assertFalse(index.recentlyPublished("any"));
        index.published("any"); assertTrue(index.recentlyPublished("any"));
    }
    @Test void validJsonWithInvalidTimestampsCannotBreakCheckpointWrites() throws Exception {
        Path file = directory.resolve("processed.txt");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Files.writeString(directory.resolve("processed.txt.pending.json"),
                "{\"missing\":null,\"negative\":-1,\"future\":" + (now.toEpochMilli() + 1)
                        + ",\"ok\":" + now.toEpochMilli() + "}");
        var index = new PendingMailIndex(file, Clock.fixed(now, ZoneOffset.UTC));
        assertFalse(index.recentlyPublished("missing"));
        assertFalse(index.recentlyPublished("negative"));
        assertFalse(index.recentlyPublished("future"));
        assertTrue(index.recentlyPublished("ok"));
        index.published("new");
        assertTrue(new PendingMailIndex(file, Clock.fixed(now, ZoneOffset.UTC)).recentlyPublished("new"));
    }
}
