package com.smartinbox.processor.news;

import com.smartinbox.processor.controller.DashboardController.NewsItem;
import com.smartinbox.processor.service.AiService;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NewsBriefService {
    private static final Map<String, List<String>> DOMAINS = Map.of(
            "CNN", List.of("cnn.com"), "NBC News", List.of("nbcnews.com"),
            "ABC News", List.of("abcnews.go.com", "abcnews.com"),
            "CBS News", List.of("cbsnews.com"), "NPR", List.of("npr.org"));
    private static final Map<String, String> HOMEPAGES = Map.of(
            "CNN", "https://www.cnn.com/", "NBC News", "https://www.nbcnews.com/",
            "ABC News", "https://abcnews.go.com/", "CBS News", "https://www.cbsnews.com/", "NPR", "https://www.npr.org/");
    private final AiService ai;
    private final Map<NewsItem, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private final ConcurrentHashMap<NewsItem, CompletableFuture<Brief>> pending = new ConcurrentHashMap<>();

    public NewsBriefService(AiService ai) { this.ai = ai; }

    public Brief summarize(NewsItem item) {
        synchronized (cache) {
            Cached hit = cache.get(item);
            if (hit != null && hit.expiresAt().isAfter(Instant.now())) return hit.brief();
        }
        CompletableFuture<Brief> request = new CompletableFuture<>();
        CompletableFuture<Brief> existing = pending.putIfAbsent(item, request);
        if (existing != null) return existing.join();
        try {
            String title = plain(item.title());
            String excerpt = plain(item.summary());
            boolean titleOnly = isTitleOnly(title, excerpt, item.source());
            var text = ai.summarizeNewsToChinese(title, titleOnly ? "" : excerpt, titleOnly);
            OfficialLink link = officialLink(item.source(), item.url());
            Brief brief = new Brief(text.title(), text.summary(), item.source(), item.publishedAt(),
                    titleOnly ? "title" : "excerpt", link.url(), link.article());
            synchronized (cache) {
                cache.put(item, new Cached(brief, Instant.now().plus(Duration.ofHours(6))));
                while (cache.size() > 200) cache.remove(cache.keySet().iterator().next());
            }
            request.complete(brief);
            return brief;
        } catch (RuntimeException error) {
            request.completeExceptionally(error);
            throw error;
        } finally { pending.remove(item, request); }
    }

    static String plain(String value) {
        return HtmlUtils.htmlUnescape(value == null ? "" : value).replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ").trim();
    }

    static boolean isTitleOnly(String title, String excerpt, String source) {
        String normalizedTitle = normalize(title);
        String normalizedExcerpt = normalize(excerpt);
        String normalizedSource = normalize(source);
        if (!normalizedSource.isEmpty() && normalizedExcerpt.endsWith(normalizedSource))
            normalizedExcerpt = normalizedExcerpt.substring(0, normalizedExcerpt.length() - normalizedSource.length());
        return normalizedExcerpt.isEmpty() || normalizedExcerpt.equals(normalizedTitle);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static boolean supportedSource(String source) { return source != null && DOMAINS.containsKey(source); }

    public static OfficialLink officialLink(String source, String value) {
        if (!supportedSource(source)) throw new IllegalArgumentException("Unknown media source");
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("https".equals(uri.getScheme()) && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && DOMAINS.get(source).stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain)))
                return new OfficialLink(uri.toString(), true);
        } catch (IllegalArgumentException | NullPointerException ignored) { }
        // Google News links are indexes, not official article URLs. Never label them as the original article.
        return new OfficialLink(HOMEPAGES.get(source), false);
    }

    public record OfficialLink(String url, boolean article) { }
    public record Brief(String title, String summary, String source, String publishedAt, String basis,
                        String officialUrl, boolean officialArticle) { }
    private record Cached(Brief brief, Instant expiresAt) { }
}
