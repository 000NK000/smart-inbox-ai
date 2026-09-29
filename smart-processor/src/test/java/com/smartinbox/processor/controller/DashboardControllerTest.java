package com.smartinbox.processor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.cache.SourceSnapshotStore;
import com.smartinbox.processor.service.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DashboardControllerTest {
    @TempDir Path cacheDirectory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final HttpClient http = mock(HttpClient.class);

    SourceSnapshotStore snapshots() {
        return new SourceSnapshotStore(mapper, cacheDirectory);
    }

    DashboardController controller() {
        return new DashboardController(mapper, mock(AiService.class), http, snapshots());
    }

    @Test void recognizesCnnPublisherUrlsAndFiltersBeforeLimitingResults() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < 35; index++) {
            items.append(item("Other " + index, "KJCT", "https://www.kjct8.com"));
        }
        items.append(item("Impersonated", "CNN", "https://cnn.com.evil.test"));
        items.append(item("User info", "CNN", "https://mailbox@example.com"));
        items.append(item("Invalid URL", "CNN", "not a url"));
        items.append(item("Other CNN brand", "CNN Brasil", "https://www.cnnbrasil.com.br"));
        for (int index = 0; index < 12; index++) {
            items.append(item("CNN headline " + index, index % 2 == 0 ? "cnn.com" : "CNN",
                    index % 2 == 0 ? "https://www.cnn.com" : "https://edition.cnn.com"));
        }
        respond(items.toString());

        var result = controller().retrySource("cnn");

        assertTrue(result.available());
        assertEquals(10, result.items().size());
        assertEquals("CNN headline 0", result.items().get(0).title());
        assertEquals("CNN headline 9", result.items().get(9).title());
        assertTrue(result.items().stream().allMatch(article -> article.source().equals("CNN")));
        var saved = snapshots().load("news-cnn", DashboardController.NewsItem.class).orElseThrow();
        assertEquals(result.items(), saved.items());
        assertEquals(result.updatedAt(), saved.savedAt());
    }

    @Test void acceptsExactLegacyLabelsOnlyWhenPublisherUrlIsAbsent() throws Exception {
        respond(item("Legacy", "CNN", "") + item("Domain label", "cnn.com", "")
                + item("Syndicated", "CNN affiliate", "")
                + item("Mislabelled", "CNN", "https://other.example"));

        var result = controller().retrySource("cnn");

        assertEquals(List.of("Legacy", "Domain label"), result.items().stream().map(DashboardController.NewsItem::title).toList());
    }

    @Test void restartsWithLastSuccessfulArticlesAndRetainsTheirOriginalTimestamp() throws Exception {
        Instant savedAt = Instant.now().minusSeconds(1200);
        var article = new DashboardController.NewsItem("Saved headline", "https://www.cnn.com/story", "CNN", "date", "summary");
        snapshots().save("news-cnn", List.of(article), savedAt);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("offline"));
        var restarted = controller();

        var first = restarted.retrySource("cnn");
        var second = restarted.retrySource("cnn");

        assertTrue(first.available());
        assertEquals("实时源暂不可用，正在显示最近缓存", first.status());
        assertEquals(savedAt, first.updatedAt());
        assertEquals(List.of(article), first.items());
        assertEquals(first, second);
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals(savedAt, snapshots().load("news-cnn", DashboardController.NewsItem.class).orElseThrow().savedAt());
    }

    @Test void failedRefreshKeepsGoodSnapshotAndDoesNotReportItAsFresh() throws Exception {
        respond(item("Good headline", "cnn.com", "https://www.cnn.com"));
        var feeds = controller();
        var good = feeds.retrySource("cnn");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("offline"));

        var failed = feeds.retrySource("cnn");
        var repeated = feeds.retrySource("cnn");

        assertEquals(good.items(), failed.items());
        assertEquals(good.updatedAt(), failed.updatedAt());
        assertEquals("实时源暂不可用，正在显示最近缓存", failed.status());
        assertEquals(failed, repeated);
        assertEquals(failed, feeds.sourceStatus().get(0));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void emptyPublisherResultsAreUnavailableAndFailureRetriesAreBounded() throws Exception {
        respond(item("Other publisher", "CNN", "https://other.example"));
        var feeds = controller();

        var first = feeds.retrySource("cnn");
        var second = feeds.retrySource("cnn");

        assertFalse(first.available());
        assertTrue(first.items().isEmpty());
        assertNull(first.updatedAt());
        assertEquals(first, second);
        assertTrue(snapshots().load("news-cnn", DashboardController.NewsItem.class).isEmpty());
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void overlappingExplicitRefreshesShareOneUpstreamRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var response = response(item("Shared headline", "cnn.com", "https://www.cnn.com"));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return response;
        });
        var feeds = controller();
        var executor = Executors.newFixedThreadPool(2);
        var contender = new AtomicReference<Thread>();
        try {
            var first = executor.submit(() -> feeds.retrySource("cnn"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                contender.set(Thread.currentThread());
                return feeds.retrySource("cnn");
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((contender.get() == null || contender.get().getState() != Thread.State.BLOCKED)
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertNotNull(contender.get());
            assertEquals(Thread.State.BLOCKED, contender.get().getState());
            release.countDown();
            assertEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void respond(String items) throws Exception {
        var upstream = response(items);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(String items) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(("<rss><channel>" + items + "</channel></rss>").getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private String item(String title, String publisher, String publisherUrl) {
        return "<item><title>" + title + " - " + publisher + "</title><link>https://news.google.com/rss/articles/"
                + title.replace(" ", "-") + "</link><source url=\"" + publisherUrl + "\">" + publisher
                + "</source><pubDate>Sun, 27 Sep 2026 10:00:00 GMT</pubDate><description>Article summary</description></item>";
    }
}
