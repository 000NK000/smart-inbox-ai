package com.smartinbox.processor.watch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.cache.SourceSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WatchServiceCacheTest {
    private static final String MOVIE = "douban-movie_weekly_best";
    private static final String SNAPSHOT_KEY = "watch-" + MOVIE;
    private static final byte[] MOVIE_PAYLOAD = """
            {"subject_collection_items":[{"id":"123","title":"Weekly leader","rank":1,
            "cover_url":"https://img1.doubanio.com/poster.jpg","rating":{"value":8.5}}]}
            """.getBytes(StandardCharsets.UTF_8);

    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.now());

    @Test void successfulRankingsSurviveRestartAndFailedRefreshWithTheirOriginalTimestamp() {
        SourceSnapshotStore store = new SourceSnapshotStore(mapper, directory);
        WatchService original = service(store, (url, referer) -> MOVIE_PAYLOAD);
        WatchSourceStatus live = original.retrySource(MOVIE);
        assertTrue(live.available());
        assertEquals("实时更新", live.status());

        clock.advance(Duration.ofMinutes(25));
        AtomicInteger attempts = new AtomicInteger();
        WatchService restarted = service(new SourceSnapshotStore(mapper, directory), (url, referer) -> {
            attempts.incrementAndGet();
            throw new IOException("Remote watch source returned HTTP 404");
        });
        WatchSourceStatus fallback = restarted.retrySource(MOVIE);
        assertTrue(fallback.available());
        assertEquals(1, fallback.itemCount());
        assertEquals(live.updatedAt(), fallback.updatedAt());
        assertEquals("实时源暂不可用，显示最近缓存", fallback.status());
        assertEquals("https://img1.doubanio.com/poster.jpg", restarted.doubanPosterUrl("123").orElseThrow());
        assertEquals(fallback, restarted.retrySource(MOVIE));
        assertEquals(1, attempts.get(), "An explicit refresh must respect a recent upstream failure");
        assertEquals(live.updatedAt(), store.load(SNAPSHOT_KEY, WatchItem.class).orElseThrow().savedAt());
    }

    @Test void missingAndEmptyCollectionsNeverReplaceTheLastSuccessfulSnapshot() {
        SourceSnapshotStore store = new SourceSnapshotStore(mapper, directory);
        Instant savedAt = service(store, (url, referer) -> MOVIE_PAYLOAD).retrySource(MOVIE).updatedAt();
        for (String payload : new String[]{"{}", "{\"subject_collection_items\":[]}",
                "{\"subject_collection_items\":[{\"id\":\"456\",\"title\":\" \"}]}"}) {
            clock.advance(Duration.ofSeconds(1));
            WatchService failing = service(store, (url, referer) -> payload.getBytes(StandardCharsets.UTF_8));
            WatchSourceStatus status = failing.retrySource(MOVIE);
            assertEquals("实时源暂不可用，显示最近缓存", status.status());
            assertEquals(savedAt, status.updatedAt());
            var snapshot = store.load(SNAPSHOT_KEY, WatchItem.class).orElseThrow();
            assertEquals(savedAt, snapshot.savedAt());
            assertEquals("Weekly leader", snapshot.items().get(0).title());
        }
    }

    @Test void failuresWithoutCacheAreThrottledAndRecoveryResumesAfterCooldown() {
        AtomicInteger attempts = new AtomicInteger();
        WatchService watch = service(new SourceSnapshotStore(mapper, directory), (url, referer) ->
                attempts.incrementAndGet() == 1 ? "{}".getBytes(StandardCharsets.UTF_8) : MOVIE_PAYLOAD);
        assertFalse(watch.retrySource(MOVIE).available());
        clock.advance(Duration.ofSeconds(59));
        assertFalse(watch.retrySource(MOVIE).available());
        assertEquals(1, attempts.get());
        clock.advance(Duration.ofSeconds(2));
        WatchSourceStatus recovered = watch.retrySource(MOVIE);
        assertTrue(recovered.available());
        assertEquals("实时更新", recovered.status());
        assertEquals(clock.instant(), recovered.updatedAt());
        assertEquals(2, attempts.get());
    }

    @Test void overlappingFailuresShareOneAttemptAndDoNotBlockOtherSources() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        WatchService watch = service(new SourceSnapshotStore(mapper, directory), (url, referer) -> {
            attempts.incrementAndGet();
            throw new IOException("source unavailable");
        });
        var pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        var results = new ArrayList<Future<WatchSourceStatus>>();
        try {
            for (int index = 0; index < 6; index++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return watch.retrySource(MOVIE);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var result : results) assertFalse(result.get(5, TimeUnit.SECONDS).available());
            assertEquals(1, attempts.get());
            assertFalse(watch.retrySource("douban-tv_chinese_best_weekly").available());
            assertEquals(2, attempts.get());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test void normalReadRetriesAfterFailureCooldownEvenWhenTheSuccessfulCacheIsStillFresh() {
        AtomicInteger movieAttempts = new AtomicInteger();
        WatchService watch = service(new SourceSnapshotStore(mapper, directory), (url, referer) -> {
            if (!url.contains("/movie_weekly_best/")) throw new IOException("Other source unavailable");
            if (movieAttempts.incrementAndGet() == 2) throw new IOException("Temporary movie source failure");
            return MOVIE_PAYLOAD;
        });
        Instant originalUpdate = watch.retrySource(MOVIE).updatedAt();
        assertEquals("实时源暂不可用，显示最近缓存", watch.retrySource(MOVIE).status());
        clock.advance(Duration.ofSeconds(61));
        WatchSourceStatus recovered = watch.overview(false).sources().stream()
                .filter(status -> MOVIE.equals(status.id())).findFirst().orElseThrow();
        assertEquals(3, movieAttempts.get());
        assertEquals("实时更新", recovered.status());
        assertTrue(recovered.updatedAt().isAfter(originalUpdate));
        WatchSourceStatus cached = watch.overview(false).sources().stream()
                .filter(status -> MOVIE.equals(status.id())).findFirst().orElseThrow();
        assertEquals("缓存有效", cached.status());
        assertEquals(3, movieAttempts.get());
    }

    @Test void overlappingSuccessfulExplicitRefreshesShareTheCompletedAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch finishFetch = new CountDownLatch(1);
        WatchService watch = service(new SourceSnapshotStore(mapper, directory), (url, referer) -> {
            attempts.incrementAndGet();
            fetching.countDown();
            assertTrue(finishFetch.await(5, TimeUnit.SECONDS));
            return MOVIE_PAYLOAD;
        });
        var pool = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        try {
            Future<WatchSourceStatus> first = pool.submit(() -> watch.retrySource(MOVIE));
            assertTrue(fetching.await(5, TimeUnit.SECONDS));
            Future<WatchSourceStatus> overlapping = pool.submit(() -> {
                waitingThread.set(Thread.currentThread());
                return watch.retrySource(MOVIE);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((waitingThread.get() == null || waitingThread.get().getState() != Thread.State.BLOCKED)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertNotNull(waitingThread.get());
            assertEquals(Thread.State.BLOCKED, waitingThread.get().getState(), "Second request must overlap the fetch");
            finishFetch.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS), overlapping.get(5, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
            assertEquals("实时更新", watch.retrySource(MOVIE).status());
            assertEquals(2, attempts.get(), "A later explicit refresh still makes a new request");
        } finally {
            finishFetch.countDown();
            pool.shutdownNow();
        }
    }

    @Test void unavailableRottenRankingsAreNotReplacedWithHardcodedTitles() {
        SourceSnapshotStore store = new SourceSnapshotStore(mapper, directory);
        WatchService watch = service(store, (url, referer) -> "<html></html>".getBytes(StandardCharsets.UTF_8));
        WatchSourceStatus status = watch.retrySource("rotten-movie");
        assertFalse(status.available());
        assertEquals(0, status.itemCount());
        assertNull(status.updatedAt());
        assertTrue(store.load("watch-rotten-movie", WatchItem.class).isEmpty());
    }

    private WatchService service(SourceSnapshotStore store, WatchService.SourceFetcher fetcher) {
        return new WatchService(mapper, store, fetcher, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
