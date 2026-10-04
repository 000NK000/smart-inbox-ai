package com.smartinbox.processor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.cache.SourceSnapshotStore;
import com.smartinbox.processor.service.AiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DashboardController.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration SOURCE_RETRY_DELAY = Duration.ofSeconds(60);
    private static final String WEIBO_HOT_SEARCH = "https://weibo.com/ajax/side/hotSearch";
    private static final String WEIBO_HOT_SEARCH_RSS = "https://rsshub.app/weibo/search/hot";
    private static final List<MediaFeed> US_MEDIA_FEEDS = List.of(
            new MediaFeed("cnn", "CNN", "https://news.google.com/rss/search?q=source%3ACNN%20(world%20OR%20international)%20when%3A7d&hl=en-US&gl=US&ceid=US%3Aen", true, "Google News 来源索引"),
            new MediaFeed("nbc", "NBC News", "https://feeds.nbcnews.com/nbcnews/public/world", false, "官方 RSS"),
            new MediaFeed("abc", "ABC News", "https://abcnews.go.com/abcnews/internationalheadlines", false, "官方 RSS"),
            new MediaFeed("cbs", "CBS News", "https://www.cbsnews.com/latest/rss/world", false, "官方 RSS"),
            new MediaFeed("npr", "NPR", "https://feeds.npr.org/1004/rss.xml", false, "官方 RSS"));
    private static final Set<String> TITLE_STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "as", "at", "by",
            "from", "is", "are", "was", "were", "be", "after", "over", "amid", "says", "say", "new", "live");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final SourceSnapshotStore snapshots;
    private final Map<String, CacheEntry<List<NewsItem>>> newsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<NewsItem>>> usMediaCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<JsonNode>> weatherCache = new ConcurrentHashMap<>();

    @Autowired
    public DashboardController(ObjectMapper objectMapper, AiService aiService) {
        this(objectMapper, aiService, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new SourceSnapshotStore(objectMapper));
    }

    DashboardController(ObjectMapper objectMapper, AiService aiService,
                        HttpClient httpClient, SourceSnapshotStore snapshots) {
        this.objectMapper = objectMapper;
        this.aiService = aiService;
        this.httpClient = httpClient;
        this.snapshots = snapshots;
    }

    @GetMapping("/news")
    public List<NewsItem> news(@RequestParam(defaultValue = "china") String region) {
        String normalized = "world".equalsIgnoreCase(region) ? "world" : "china";
        CacheEntry<List<NewsItem>> cached = newsCache.get(normalized);
        if (cached != null && cached.isFresh()) {
            return cached.value();
        }

        try {
            List<NewsItem> items;
            if ("world".equals(normalized)) {
                items = loadTopUsMediaNews();
            } else {
                try {
                    items = parseWeiboHotSearch(fetch(WEIBO_HOT_SEARCH)).stream().limit(10).toList();
                } catch (Exception directApiError) {
                    items = parseRss(fetch(WEIBO_HOT_SEARCH_RSS), normalized).stream().limit(10).toList();
                }
            }
            newsCache.put(normalized, new CacheEntry<>(items, Instant.now()));
            return items;
        } catch (Exception error) {
            if (cached != null) {
                return cached.value();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "News feed is temporarily unavailable", error);
        }
    }

    private List<NewsItem> loadTopUsMediaNews() {
        return usNews(false).combined();
    }

    @GetMapping("/us-news")
    public UsNewsOverview usNews(@RequestParam(defaultValue = "false") boolean refresh) {
        List<CompletableFuture<MediaSourceResult>> requests = US_MEDIA_FEEDS.stream()
                .map(feed -> CompletableFuture.supplyAsync(() -> loadMediaSource(feed, refresh)))
                .toList();
        List<MediaSourceResult> sources = requests.stream().map(CompletableFuture::join).toList();
        List<RankedNews> all = new ArrayList<>();
        for (MediaSourceResult source : sources) {
            for (int index = 0; index < source.items().size(); index++) {
                all.add(new RankedNews(source.items().get(index), index));
            }
        }
        List<NewsItem> combined = rankTopUsMediaNews(all);
        newsCache.put("world", new CacheEntry<>(combined, Instant.now()));
        return new UsNewsOverview(combined, sources);
    }

    @PostMapping("/us-news/translate")
    public UsNewsTranslationResponse translateUsNews(@RequestBody UsNewsTranslationRequest request) {
        List<NewsItem> items = request == null || request.items() == null
                ? List.of()
                : request.items().stream().limit(10).toList();
        if (items.isEmpty()) {
            return new UsNewsTranslationResponse("zh-CN", List.of());
        }

        List<AiService.NewsTranslationInput> inputs = items.stream()
                .map(item -> new AiService.NewsTranslationInput(item.title(), item.summary()))
                .toList();
        try {
            List<AiService.NewsTranslation> translations = aiService.translateNewsToChinese(inputs);
            List<NewsItem> translated = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                NewsItem original = items.get(index);
                AiService.NewsTranslation translation = translations.get(index);
                translated.add(new NewsItem(
                        translation.title(),
                        original.url(),
                        original.source(),
                        original.publishedAt(),
                        translation.summary()));
            }
            return new UsNewsTranslationResponse("zh-CN", List.copyOf(translated));
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Local AI translation is temporarily unavailable",
                    error);
        }
    }

    /** Resolve the original feed item, even when the UI is showing a translated/combined row. */
    public java.util.Optional<NewsItem> findUsNewsItem(String source, String url) {
        var found = cachedUsNewsItem(source, url);
        if (found.isPresent()) return found;
        usNews(false);
        return cachedUsNewsItem(source, url);
    }

    private java.util.Optional<NewsItem> cachedUsNewsItem(String source, String url) {
        return usMediaCache.values().stream().flatMap(entry -> entry.value().stream())
                .filter(item -> item.source().equals(source) && item.url().equals(url)).findFirst();
    }

    private final Map<String,MediaSourceResult> sourceHealth = new ConcurrentHashMap<>();
    private final Map<String,Object> sourceLocks = new ConcurrentHashMap<>();
    private final Map<String,Long> sourceRevisions = new ConcurrentHashMap<>();
    private final Map<String,Instant> sourceRetryAfter = new ConcurrentHashMap<>();
    public List<MediaSourceResult> sourceStatus() { return List.copyOf(sourceHealth.values()); }
    public MediaSourceResult retrySource(String id) {
        return loadMediaSource(US_MEDIA_FEEDS.stream().filter(f->f.id().equals(id)).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown news source")),true);
    }
    private MediaSourceResult loadMediaSource(MediaFeed feed, boolean refresh) {
        long observedRevision = sourceRevisions.getOrDefault(feed.id(), 0L);
        synchronized (sourceLocks.computeIfAbsent(feed.id(), ignored -> new Object())) {
            MediaSourceResult previous = sourceHealth.get(feed.id());
            // Requests queued during an upstream fetch share its result, including explicit refreshes.
            // Revisions distinguish sequential requests even when the wall clock has not advanced.
            if (sourceRevisions.getOrDefault(feed.id(), 0L) != observedRevision && previous != null) {
                return previous;
            }
            var value = readMediaSource(feed, refresh);
            sourceHealth.put(feed.id(), value);
            sourceRevisions.put(feed.id(), observedRevision + 1);
            return value;
        }
    }
    private MediaSourceResult readMediaSource(MediaFeed feed, boolean refresh) {
        usMediaCache.computeIfAbsent(feed.id(), ignored -> snapshots.load("news-" + feed.id(), NewsItem.class)
                .map(saved -> new CacheEntry<>(saved.items(), saved.savedAt())).orElse(null));
        CacheEntry<List<NewsItem>> cached = usMediaCache.get(feed.id());
        Instant retryAfter = sourceRetryAfter.get(feed.id());
        if (retryAfter != null && retryAfter.isAfter(Instant.now())) {
            return unavailableMediaSource(feed, cached);
        }
        if (!refresh && retryAfter == null && cached != null && cached.isFresh()) {
            return new MediaSourceResult(feed.id(), feed.name(), feed.channel(), true,
                    "缓存有效", cached.createdAt(), cached.value());
        }
        try {
            List<NewsItem> parsed = parseRss(fetch(feed.url()), "world", feed);
            List<NewsItem> items = new ArrayList<>();
            for (NewsItem item : parsed) {
                items.add(new NewsItem(item.title(), item.url(), feed.name(), item.publishedAt(), item.summary()));
                if (items.size() == 10) {
                    break;
                }
            }
            if (items.isEmpty()) {
                throw new IllegalStateException(feed.name() + " returned no usable articles");
            }
            CacheEntry<List<NewsItem>> loaded = new CacheEntry<>(List.copyOf(items), Instant.now());
            usMediaCache.put(feed.id(), loaded);
            snapshots.save("news-" + feed.id(), loaded.value(), loaded.createdAt());
            sourceRetryAfter.remove(feed.id());
            return new MediaSourceResult(feed.id(), feed.name(), feed.channel(), true,
                    "实时更新", loaded.createdAt(), loaded.value());
        } catch (Exception error) {
            sourceRetryAfter.put(feed.id(), Instant.now().plus(SOURCE_RETRY_DELAY));
            LOGGER.warn("Unable to refresh news source {}: {}", feed.id(), error.toString());
            return unavailableMediaSource(feed, cached);
        }
    }

    private MediaSourceResult unavailableMediaSource(MediaFeed feed, CacheEntry<List<NewsItem>> cached) {
        if (cached != null && !cached.value().isEmpty()) {
            return new MediaSourceResult(feed.id(), feed.name(), feed.channel(), true,
                    "实时源暂不可用，正在显示最近缓存", cached.createdAt(), cached.value());
        }
        return new MediaSourceResult(feed.id(), feed.name(), feed.channel(), false,
                "暂时无法连接数据源", null, List.of());
    }

    private List<NewsItem> rankTopUsMediaNews(List<RankedNews> all) {
        if (all.isEmpty()) {
            throw new IllegalStateException("All U.S. media feeds were unavailable");
        }

        List<NewsCluster> clusters = new ArrayList<>();
        for (RankedNews article : all) {
            Set<String> tokens = titleTokens(article.item().title());
            if (tokens.isEmpty()) {
                continue;
            }
            NewsCluster closest = null;
            double bestSimilarity = 0;
            for (NewsCluster cluster : clusters) {
                double similarity = similarity(tokens, cluster.topicTokens);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    closest = cluster;
                }
            }
            if (closest != null && bestSimilarity >= 0.38) {
                closest.add(article);
            } else {
                clusters.add(new NewsCluster(article, tokens));
            }
        }

        List<NewsCluster> sorted = clusters.stream()
                .sorted(Comparator.comparingDouble(NewsCluster::score).reversed())
                .toList();
        List<NewsCluster> selected = new ArrayList<>();
        for (MediaFeed feed : US_MEDIA_FEEDS) {
            sorted.stream().filter(cluster -> cluster.hasSource(feed.name())).findFirst()
                    .ifPresent(cluster -> { if (!selected.contains(cluster)) selected.add(cluster); });
        }
        for (NewsCluster cluster : sorted) {
            if (selected.size() >= 10) break;
            if (!selected.contains(cluster)) selected.add(cluster);
        }
        return selected.stream()
                .sorted(Comparator.comparingDouble(NewsCluster::score).reversed())
                .limit(10)
                .map(NewsCluster::asNewsItem)
                .toList();
    }

    private Set<String> titleTokens(String title) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 2 && !TITLE_STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private double similarity(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private long freshnessScore(String publishedAt) {
        try {
            Instant published = ZonedDateTime.parse(publishedAt, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long hours = Math.max(0, Duration.between(published, Instant.now()).toHours());
            return Math.max(0, 72 - hours);
        } catch (Exception ignored) {
            return 0;
        }
    }

    @GetMapping("/weather")
    public JsonNode weather(@RequestParam double latitude, @RequestParam double longitude) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid coordinates");
        }
        String cacheKey = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude);
        CacheEntry<JsonNode> cached = weatherCache.get(cacheKey);
        if (cached != null && cached.isFresh()) {
            return cached.value();
        }

        String url = "https://api.open-meteo.com/v1/forecast?latitude="
                + encode(Double.toString(latitude)) + "&longitude=" + encode(Double.toString(longitude))
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weather_code,wind_speed_10m"
                + "&timezone=auto&forecast_days=2";
        try {
            JsonNode weather = objectMapper.readTree(fetch(url));
            weatherCache.put(cacheKey, new CacheEntry<>(weather, Instant.now()));
            return weather;
        } catch (Exception error) {
            if (cached != null) {
                return cached.value();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Weather service is temporarily unavailable", error);
        }
    }

    private final Map<String,CacheEntry<JsonNode>> rainCache = new ConcurrentHashMap<>();
    @PostMapping("/weather/analysis")
    public synchronized JsonNode analyzeWeather(@RequestBody WeatherAnalysisRequest request) {
        if(request==null || request.hours()==null || !request.hours().isArray() || request.hours().size()>25 || request.location()==null || request.location().length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid weather input");
        String key=request.location()+":"+request.hours();
        var cached=rainCache.get(key);
        if(cached!=null && cached.isFresh()) return cached.value();
        var result=aiService.analyzeRainForecast(request.location(),request.hours());
        if(result.path("aiGenerated").asBoolean(false)) {
            if(rainCache.size()>=64) rainCache.entrySet().stream().min(java.util.Comparator.comparing(e->e.getValue().createdAt())).ifPresent(e->rainCache.remove(e.getKey()));
            rainCache.put(key,new CacheEntry<>(result,Instant.now()));
        }
        return result;
    }

    private byte[] fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36")
                .header("Accept", "application/json, application/rss+xml, application/xml, text/xml, */*")
                .header("Referer", "https://weibo.com/")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Remote service returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private List<NewsItem> parseWeiboHotSearch(byte[] payload) throws Exception {
        JsonNode realtime = objectMapper.readTree(payload).path("data").path("realtime");
        if (!realtime.isArray()) {
            throw new IllegalStateException("Weibo hot-search response did not contain a realtime list");
        }

        List<NewsItem> result = new ArrayList<>();
        for (JsonNode item : realtime) {
            String word = item.path("word").asText("").trim();
            if (word.isBlank() || item.path("is_ad").asInt(0) == 1) {
                continue;
            }
            String query = item.path("word_scheme").asText("").trim();
            if (query.isBlank()) {
                query = "#" + word + "#";
            }
            long heat = item.path("raw_hot").asLong(item.path("num").asLong(0));
            String label = item.path("label_name").asText("").trim();
            String summary = heat > 0 ? "实时热度 " + String.format(Locale.CHINA, "%,d", heat) : "微博实时热搜";
            if (!label.isBlank()) {
                summary += " · " + label;
            }
            result.add(new NewsItem(word,
                    "https://s.weibo.com/weibo?q=" + encode(query),
                    "微博热搜",
                    Instant.now().toString(),
                    summary));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Weibo hot-search response was empty");
        }
        return result;
    }

    private List<NewsItem> parseRss(byte[] xml, String region) throws Exception {
        return parseRss(xml, region, null);
    }

    private List<NewsItem> parseRss(byte[] xml, String region, MediaFeed feed) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);

        NodeList nodes = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml))
                .getElementsByTagName("item");
        List<NewsItem> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength() && result.size() < 30; index++) {
            Element item = (Element) nodes.item(index);
            String title = childText(item, "title");
            String link = childText(item, "link");
            String source = childText(item, "source");
            // Google News changes publisher labels (CNN / cnn.com); use the publisher URL
            // when supplied and filter before applying the article limit.
            if (feed != null && feed.strictSourceFilter() && !matchesPublisher(feed, item, source)) {
                continue;
            }
            String publishedAt = childText(item, "pubDate");
            String summary = cleanHtml(childText(item, "description"));
            if (title.isBlank() || link.isBlank()) {
                continue;
            }
            if (source.isBlank()) {
                source = "world".equals(region) ? "" : "微博热搜";
            }
            result.add(new NewsItem(cleanTitle(title), link, source, publishedAt, summary));
        }
        return result;
    }

    private boolean matchesPublisher(MediaFeed feed, Element item, String source) {
        if (!"cnn".equals(feed.id())) {
            return feed.name().equalsIgnoreCase(source);
        }
        NodeList sources = item.getElementsByTagName("source");
        String publisherUrl = sources.getLength() == 0 ? ""
                : ((Element) sources.item(0)).getAttribute("url").trim();
        if (publisherUrl.isBlank()) {
            return "CNN".equalsIgnoreCase(source) || "cnn.com".equalsIgnoreCase(source);
        }
        try {
            URI publisher = URI.create(publisherUrl);
            String host = publisher.getHost();
            return ("https".equalsIgnoreCase(publisher.getScheme()) || "http".equalsIgnoreCase(publisher.getScheme()))
                    && publisher.getUserInfo() == null && host != null
                    && (host.equalsIgnoreCase("cnn.com") || host.toLowerCase(Locale.ROOT).endsWith(".cnn.com"));
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    private String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        return node == null || node.getTextContent() == null ? "" : node.getTextContent().trim();
    }

    private String sourceFromTitle(String title) {
        int separator = title.lastIndexOf(" - ");
        return separator > 0 ? title.substring(separator + 3).trim() : "Google 新闻";
    }

    private String cleanTitle(String title) {
        int separator = title.lastIndexOf(" - ");
        return separator > 0 ? title.substring(0, separator).trim() : title.trim();
    }

    private String cleanHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record NewsItem(String title, String url, String source, String publishedAt, String summary) {
    }

    public record MediaSourceResult(String id, String name, String channel, boolean available,
                                    String status, Instant updatedAt, List<NewsItem> items) {
    }

    public record UsNewsOverview(List<NewsItem> combined, List<MediaSourceResult> sources) {
    }

    public record UsNewsTranslationRequest(List<NewsItem> items) {
    }

    public record UsNewsTranslationResponse(String language, List<NewsItem> items) {
    }

    public record WeatherAnalysisRequest(String location, JsonNode hours) {
    }

    private record MediaFeed(String id, String name, String url, boolean strictSourceFilter, String channel) {
    }

    private record RankedNews(NewsItem item, int feedRank) {
    }

    private final class NewsCluster {
        private final List<RankedNews> articles = new ArrayList<>();
        private final Set<String> topicTokens;

        private NewsCluster(RankedNews first, Set<String> topicTokens) {
            this.topicTokens = topicTokens;
            add(first);
        }

        private void add(RankedNews article) {
            articles.add(article);
        }

        private double score() {
            long sourceCount = articles.stream().map(article -> article.item().source()).distinct().count();
            int bestFeedRank = articles.stream().mapToInt(RankedNews::feedRank).min().orElse(30);
            long newest = articles.stream().mapToLong(article -> freshnessScore(article.item().publishedAt())).max().orElse(0);
            return sourceCount * 1000 + Math.max(0, 100 - bestFeedRank * 4) + newest;
        }

        private boolean hasSource(String source) {
            return articles.stream().anyMatch(article -> source.equalsIgnoreCase(article.item().source()));
        }

        private NewsItem asNewsItem() {
            RankedNews representative = articles.stream()
                    .min(Comparator.comparingInt(RankedNews::feedRank))
                    .orElse(articles.get(0));
            long sourceCount = articles.stream().map(article -> article.item().source()).distinct().count();
            NewsItem item = representative.item();
            String summary = sourceCount > 1
                    ? sourceCount + " 家美国媒体正在关注这一主题。"
                    : item.summary();
            return new NewsItem(item.title(), item.url(), item.source(), item.publishedAt(), summary);
        }
    }

    private record CacheEntry<T>(T value, Instant createdAt) {
        private boolean isFresh() {
            return createdAt.plus(CACHE_TTL).isAfter(Instant.now());
        }
    }
}
