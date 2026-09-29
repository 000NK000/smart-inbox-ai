package com.smartinbox.processor.watch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.cache.SourceSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WatchService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(20);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(60);
    private static final String IMDB_MOVIES = "https://v3-cinemeta.strem.io/catalog/movie/top.json";
    private static final String IMDB_TV = "https://v3-cinemeta.strem.io/catalog/series/top.json";
    private static final String ROTTEN_MOVIES = "https://editorial.rottentomatoes.com/guide/popular-movies/";
    private static final String ROTTEN_TV = "https://editorial.rottentomatoes.com/guide/popular-tv-shows/";
    private static final Pattern ROTTEN_RANK = Pattern.compile(
            "(?is)<div[^>]*class=\\\"[^\\\"]*countdown-index[^\\\"]*\\\"[^>]*>\\s*#?(\\d+)\\s*</div>");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final SourceSnapshotStore snapshots;
    private final SourceFetcher fetcher;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> sourceLocks = new ConcurrentHashMap<>();
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();
    private final Map<String, CompletedAttempt> completedAttempts = new ConcurrentHashMap<>();

    @Autowired
    public WatchService(ObjectMapper objectMapper) {
        this(objectMapper, new SourceSnapshotStore(objectMapper), null, Clock.systemUTC());
    }

    WatchService(ObjectMapper objectMapper, SourceSnapshotStore snapshots, SourceFetcher fetcher, Clock clock) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
        this.fetcher = fetcher == null ? this::fetch : fetcher;
        this.clock = clock;
    }

    public java.util.Optional<String> doubanPosterUrl(String subjectId) {
        return cache.values().stream().flatMap(entry -> entry.items().stream())
                .filter(item -> "豆瓣".equals(item.source()) && subjectId.equals(item.id()))
                .map(WatchItem::poster).filter(value -> value != null && !value.isBlank()).findFirst();
    }

    public WatchOverview overview(boolean refresh) {
        List<CompletableFuture<WatchCollection>> doubanCharts = Arrays.stream(DoubanWeeklyChart.values()).map(chart ->
                loadAsync("douban-" + chart.id, "豆瓣", chart.title, chart.apiUrl(), SourceType.DOUBAN, refresh)
                        .thenApply(result -> new WatchCollection(chart.id, chart.label, chart.title, chart.kind,
                                chart.pageUrl(), result.items(), result.status()))).toList();
        CompletableFuture<SourceResult> imdbMovies = loadAsync("imdb-movie", "IMDb", "IMDb 条目 · 热度索引", IMDB_MOVIES, SourceType.IMDB, refresh);
        CompletableFuture<SourceResult> rottenMovies = loadAsync("rotten-movie", "烂番茄", "官方 Most Popular Movies", ROTTEN_MOVIES, SourceType.ROTTEN_MOVIE, refresh);
        CompletableFuture<SourceResult> imdbTv = loadAsync("imdb-tv", "IMDb", "IMDb 条目 · 热度索引", IMDB_TV, SourceType.IMDB, refresh);
        CompletableFuture<SourceResult> rottenTv = loadAsync("rotten-tv", "烂番茄", "官方 Most Popular TV Shows", ROTTEN_TV, SourceType.ROTTEN_TV, refresh);

        List<WatchCollection> collections = doubanCharts.stream().map(CompletableFuture::join).toList();
        SourceResult imdbMovie = imdbMovies.join();
        SourceResult rtMovie = rottenMovies.join();
        SourceResult imdbSeries = imdbTv.join();
        SourceResult rtSeries = rottenTv.join();
        List<WatchSourceStatus> sources = new ArrayList<>(collections.stream().map(WatchCollection::status).toList());
        sources.addAll(List.of(imdbMovie.status(), rtMovie.status(), imdbSeries.status(), rtSeries.status()));
        return new WatchOverview(
                List.of(
                        new WatchRanking("douban", "豆瓣", collections.get(0).items(), collections),
                        new WatchRanking("imdb", "IMDb", imdbMovie.items()),
                        new WatchRanking("rotten", "烂番茄", rtMovie.items())),
                List.of(
                        new WatchRanking("douban", "豆瓣", collections.get(1).items(), collections),
                        new WatchRanking("imdb", "IMDb", imdbSeries.items()),
                        new WatchRanking("rotten", "烂番茄", rtSeries.items())),
                sources,
                sources.stream().map(WatchSourceStatus::updatedAt).filter(value -> value != null).max(Instant::compareTo).orElse(Instant.now()));
    }

    private CompletableFuture<SourceResult> loadAsync(String id, String name, String channel, String url,
                                                       SourceType type, boolean refresh) {
        return CompletableFuture.supplyAsync(() -> load(id, name, channel, url, type, refresh));
    }

    private final Map<String,WatchSourceStatus> statuses = new ConcurrentHashMap<>();
    public List<WatchSourceStatus> sourceStatus() { return List.copyOf(statuses.values()); }
    public WatchSourceStatus retrySource(String id) {
        for(var chart:DoubanWeeklyChart.values()) if(id.equals("douban-"+chart.id)) return load(id,"豆瓣",chart.title,chart.apiUrl(),SourceType.DOUBAN,true).status();
        return switch(id) {
            case "imdb-movie" -> load(id,"IMDb","IMDb 条目 · 热度索引",IMDB_MOVIES,SourceType.IMDB,true).status();
            case "imdb-tv" -> load(id,"IMDb","IMDb 条目 · 热度索引",IMDB_TV,SourceType.IMDB,true).status();
            case "rotten-movie" -> load(id,"烂番茄","官方 Most Popular Movies",ROTTEN_MOVIES,SourceType.ROTTEN_MOVIE,true).status();
            case "rotten-tv" -> load(id,"烂番茄","官方 Most Popular TV Shows",ROTTEN_TV,SourceType.ROTTEN_TV,true).status();
            default -> throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Unknown watch source");
        };
    }
    private SourceResult load(String id, String name, String channel, String url, SourceType type, boolean refresh) {
        long requestedAt = System.nanoTime();
        synchronized (sourceLocks.computeIfAbsent(id, ignored -> new Object())) {
            CompletedAttempt completed = completedAttempts.get(id);
            if (completed != null && completed.finishedAtNanos() - requestedAt >= 0) {
                return completed.result();
            }
            var value = readSource(id, name, channel, url, type, refresh);
            statuses.put(id, value.status());
            return value;
        }
    }
    private SourceResult readSource(String id, String name, String channel, String url, SourceType type, boolean refresh) {
        CacheEntry cached = cache.computeIfAbsent(id, key -> snapshots.load("watch-" + key, WatchItem.class)
                .map(snapshot -> new CacheEntry(snapshot.items(), snapshot.savedAt())).orElse(null));
        Instant now = clock.instant();
        if (retryAfter.getOrDefault(id, Instant.MIN).isAfter(now)) {
            return unavailable(id, name, channel, cached);
        }
        if (!refresh && !retryAfter.containsKey(id) && cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(now)) {
            return result(id, name, channel, "缓存有效", cached, true);
        }
        try {
            String referer = switch (type) {
                case DOUBAN -> "https://m.douban.com/subject_collection/" + id.substring("douban-".length());
                case IMDB -> "https://www.imdb.com/";
                case ROTTEN_MOVIE, ROTTEN_TV -> "https://www.rottentomatoes.com/";
            };
            byte[] payload = fetcher.fetch(url, referer);
            List<WatchItem> items = switch (type) {
                case DOUBAN -> parseDouban(payload, channel);
                case IMDB -> parseImdbIndex(payload);
                case ROTTEN_MOVIE -> parseRottenTomatoes(payload, true);
                case ROTTEN_TV -> parseRottenTomatoes(payload, false);
            };
            if (items.isEmpty()) {
                throw new IllegalStateException("Source " + id + " returned no usable titles");
            }
            CacheEntry loaded = new CacheEntry(List.copyOf(items), clock.instant());
            cache.put(id, loaded);
            snapshots.save("watch-" + id, loaded.items(), loaded.createdAt());
            retryAfter.remove(id);
            return completeAttempt(id, result(id, name, channel, "实时更新", loaded, true));
        } catch (Exception error) {
            LOGGER.warn("Unable to refresh {}: {}", id, error.toString());
            retryAfter.put(id, clock.instant().plus(FAILURE_COOLDOWN));
            return completeAttempt(id, unavailable(id, name, channel, cached));
        }
    }

    private SourceResult completeAttempt(String id, SourceResult result) {
        completedAttempts.put(id, new CompletedAttempt(result, System.nanoTime()));
        return result;
    }

    private SourceResult unavailable(String id, String name, String channel, CacheEntry cached) {
        if (cached != null && !cached.items().isEmpty()) {
            return result(id, name, channel, "实时源暂不可用，显示最近缓存", cached, true);
        }
        WatchSourceStatus status = new WatchSourceStatus(id, name, channel, false, "暂时无法连接数据源", null, 0);
        return new SourceResult(List.of(), status);
    }

    private SourceResult result(String id, String name, String channel, String message, CacheEntry entry, boolean available) {
        return new SourceResult(entry.items(), new WatchSourceStatus(
                id, name, channel, available, message, entry.createdAt(), entry.items().size()));
    }

    private byte[] fetch(String url, String referer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(24))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", referer)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
            throw new IllegalStateException("Remote watch source returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    List<WatchItem> parseDouban(byte[] payload, String chartTitle) throws Exception {
        JsonNode items = objectMapper.readTree(payload).path("subject_collection_items");
        List<WatchItem> result = new ArrayList<>();
        if (!items.isArray()) {
            return result;
        }
        for (JsonNode item : items) {
            String id = text(item, "id");
            String title = text(item, "title");
            if (title.isBlank()) {
                continue;
            }
            String url = text(item, "url");
            if (url.isBlank() && !id.isBlank()) {
                url = "https://movie.douban.com/subject/" + id + "/";
            }
            String poster = text(item, "cover_url");
            if (poster.isBlank()) poster = item.path("pic").path("normal").asText("");
            if (poster.isBlank()) poster = item.path("cover").path("url").asText("");
            poster = poster.replace("\\/", "/");
            Double rating = decimal(item.path("rating").path("value"));
            String year = firstYear(text(item, "card_subtitle"));
            result.add(new WatchItem(id, title, url, poster, "豆瓣", chartTitle,
                    year, rating, item.path("rank").asInt(result.size() + 1),
                    text(item, "description"), text(item, "card_subtitle")));
            if (result.size() == 10) {
                break;
            }
        }
        return result;
    }

    private List<WatchItem> parseImdbIndex(byte[] payload) throws Exception {
        JsonNode items = objectMapper.readTree(payload).path("metas");
        List<WatchItem> result = new ArrayList<>();
        if (!items.isArray()) {
            return result;
        }
        for (JsonNode item : items) {
            String id = text(item, "imdb_id");
            String title = text(item, "name");
            if (id.isBlank() || title.isBlank()) {
                continue;
            }
            result.add(new WatchItem(id, title, "https://www.imdb.com/title/" + id + "/",
                    text(item, "poster"), "IMDb", "IMDb 条目 · 综合热度", text(item, "year"),
                    decimal(item.path("imdbRating")), result.size() + 1, text(item, "description"), ""));
            if (result.size() == 10) {
                break;
            }
        }
        return result;
    }

    private List<WatchItem> parseRottenTomatoes(byte[] payload, boolean movie) {
        String html = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        List<WatchItem> result = new ArrayList<>();
        Matcher ranks = ROTTEN_RANK.matcher(html);
        List<RankSlice> slices = new ArrayList<>();
        while (ranks.find()) {
            slices.add(new RankSlice(Integer.parseInt(ranks.group(1)), ranks.start(), ranks.end()));
        }
        for (int index = 0; index < slices.size() && result.size() < 10; index++) {
            RankSlice rank = slices.get(index);
            if (rank.rank() < 1 || rank.rank() > 10) {
                continue;
            }
            int end = index + 1 < slices.size() ? slices.get(index + 1).start() : Math.min(html.length(), rank.end() + 12000);
            String block = html.substring(rank.end(), end);
            Matcher titleLink = Pattern.compile("(?is)<h2[^>]*>.*?<a[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>").matcher(block);
            if (!titleLink.find()) {
                continue;
            }
            String url = htmlText(titleLink.group(1));
            if (url.startsWith("/")) {
                url = "https://www.rottentomatoes.com" + url;
            }
            String title = htmlText(titleLink.group(2));
            String year = match(block, "\\(((?:19|20)\\d{2})\\)");
            String score = match(block, "(?is)(?:tMeterScore[^>]*>|tomatometerscore[^>]*[=:]\\s*['\\\"]?)(\\d{1,3})%?");
            if (score.isBlank()) {
                score = match(block, "(\\d{1,3})%");
            }
            Double rating = score.isBlank() ? null : Double.valueOf(score);
            result.add(new WatchItem("rt-" + (movie ? "movie-" : "tv-") + rank.rank(), title, url, "",
                    "烂番茄", movie ? "Most Popular Movies" : "Most Popular TV Shows", year, rating, rank.rank()));
        }
        if (result.size() < 10) {
            throw new IllegalStateException("Rotten Tomatoes page contained only " + result.size() + " ranked titles");
        }
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private Double decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            String value = node.asText("").trim();
            return value.isBlank() ? null : Double.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstYear(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group() : "";
    }

    private String match(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String htmlText(String value) {
        return value.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private enum SourceType { DOUBAN, IMDB, ROTTEN_MOVIE, ROTTEN_TV }

    @FunctionalInterface
    interface SourceFetcher {
        byte[] fetch(String url, String referer) throws Exception;
    }

    private record RankSlice(int rank, int start, int end) {
    }

    private record CacheEntry(List<WatchItem> items, Instant createdAt) {
    }

    private record SourceResult(List<WatchItem> items, WatchSourceStatus status) {
    }

    private record CompletedAttempt(SourceResult result, long finishedAtNanos) {
    }
}
