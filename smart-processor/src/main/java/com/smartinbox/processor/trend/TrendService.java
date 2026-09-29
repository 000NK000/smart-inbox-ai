package com.smartinbox.processor.trend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TrendService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendService.class);

    private final List<TrendAdapter> adapters;
    private final Map<String, TrendPlatform> cache = new ConcurrentHashMap<>();

    public TrendService(ObjectMapper objectMapper) {
        TrendHttpClient httpClient = new TrendHttpClient();
        this.adapters = List.of(
                new WeiboTrendAdapter(objectMapper, httpClient),
                new BilibiliTrendAdapter(objectMapper, httpClient),
                new PendingTrendAdapter("douyin", "抖音", "等待接入合规、稳定的数据服务"),
                new PendingTrendAdapter("xiaohongshu", "小红书", "等待接入合规、稳定的数据服务"));
    }

    public List<TrendPlatform> overview(boolean refresh) {
        List<CompletableFuture<TrendPlatform>> requests = adapters.stream()
                .map(adapter -> CompletableFuture.supplyAsync(() -> load(adapter, refresh)))
                .toList();
        return requests.stream().map(CompletableFuture::join).toList();
    }

    private final Map<String,TrendPlatform> statuses = new ConcurrentHashMap<>();
    public List<TrendPlatform> sourceStatus() { return List.copyOf(statuses.values()); }
    public TrendPlatform retrySource(String id) {
        return load(adapters.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Unknown trend source")),true);
    }
    private TrendPlatform load(TrendAdapter adapter, boolean refresh) {
        var result=read(adapter,refresh); statuses.put(adapter.id(),result); return result;
    }
    private TrendPlatform read(TrendAdapter adapter, boolean refresh) {
        if (!adapter.enabled()) {
            return new TrendPlatform(adapter.id(), adapter.name(), false, false,
                    adapter.disabledReason(), null, List.of());
        }

        TrendPlatform cached = cache.get(adapter.id());
        if (!refresh && cached != null && cached.updatedAt() != null
                && Duration.between(cached.updatedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }

        try {
            TrendPlatform loaded = new TrendPlatform(adapter.id(), adapter.name(), true, true,
                    "实时更新", Instant.now(), adapter.fetch());
            cache.put(adapter.id(), loaded);
            return loaded;
        } catch (Exception error) {
            LOGGER.warn("Unable to refresh {} trends: {}", adapter.id(), error.toString());
            if (cached != null && !cached.items().isEmpty()) {
                return new TrendPlatform(cached.id(), cached.name(), true, true,
                        "实时源暂不可用，正在显示最近缓存", cached.updatedAt(), cached.items());
            }
            return new TrendPlatform(adapter.id(), adapter.name(), true, false,
                    "暂时无法连接数据源", null, List.of());
        }
    }
}
