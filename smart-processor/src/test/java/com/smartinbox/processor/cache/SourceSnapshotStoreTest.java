package com.smartinbox.processor.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceSnapshotStoreTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void snapshotSurvivesRestartWithOriginalTimeAndUnicode() {
        Instant time = Instant.now().minus(2, ChronoUnit.DAYS);
        var store = new SourceSnapshotStore(mapper, directory);
        var items = List.of(new Item("豆瓣影片", "https://example.com/1"));
        store.save("watch-movie", items, time);
        var restored = new SourceSnapshotStore(mapper, directory).load("watch-movie", Item.class).orElseThrow();
        assertEquals(items, restored.items());
        assertEquals(time, restored.savedAt());
    }

    @Test void emptyRefreshDoesNotEraseLastSuccess() {
        var store = new SourceSnapshotStore(mapper, directory);
        store.save("news-cnn", List.of(new Item("Headline", "https://cnn.com/1")), Instant.now());
        store.save("news-cnn", List.of(), Instant.now());
        assertEquals(1, store.load("news-cnn", Item.class).orElseThrow().items().size());
    }

    @Test void missingCorruptExpiredAndFutureSnapshotsAreIgnored() throws Exception {
        var store = new SourceSnapshotStore(mapper, directory);
        assertTrue(store.load("missing", Item.class).isEmpty());
        Files.writeString(directory.resolve("broken.json"), "{not json");
        assertTrue(store.load("broken", Item.class).isEmpty());
        store.save("old", List.of(new Item("old", "url")), Instant.now().minus(8, ChronoUnit.DAYS));
        assertTrue(store.load("old", Item.class).isEmpty());
        store.save("future", List.of(new Item("future", "url")), Instant.now().plus(1, ChronoUnit.DAYS));
        assertTrue(store.load("future", Item.class).isEmpty());
    }

    @Test void oversizeAndTraversalDoNotReadOrWriteOutsideCache() throws Exception {
        var store = new SourceSnapshotStore(mapper, directory);
        assertThrows(IllegalArgumentException.class, () -> store.load("../outside", Item.class));
        assertThrows(IllegalArgumentException.class, () -> store.save("../outside", List.of("value"), Instant.now()));
        Files.writeString(directory.resolve("large.json"), "x".repeat(2 * 1024 * 1024 + 1));
        assertTrue(store.load("large", Item.class).isEmpty());
    }

    @Test void unwritableCacheDoesNotBreakLiveResult() throws Exception {
        Path regularFile = directory.resolve("not-a-directory");
        Files.writeString(regularFile, "keep");
        var store = new SourceSnapshotStore(mapper, regularFile);
        assertDoesNotThrow(() -> store.save("news-cnn", List.of("live result"), Instant.now()));
        assertEquals("keep", Files.readString(regularFile));
    }

    public record Item(String title, String url) { }
}
