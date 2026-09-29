package com.smartinbox.processor.watch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WatchPosterService {
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CACHE_BYTES = 16 * 1024 * 1024;
    private final WatchService watchService;
    private final HttpClient client;
    private final Map<String, Poster> cache = new LinkedHashMap<>(32, .75f, true);
    private int cacheBytes;

    @Autowired
    public WatchPosterService(WatchService watchService) {
        this(watchService, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WatchPosterService(WatchService watchService, HttpClient client) {
        this.watchService = watchService; this.client = client;
    }

    public Poster get(String id) {
        if (!id.matches("[0-9]{1,20}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String url = watchService.doubanPosterUrl(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "暂无封面，请刷新榜单"));
        URI uri = allowedPoster(url);
        synchronized (cache) {
            Poster hit = cache.get(url);
            if (hit != null && hit.expiresAt().isAfter(Instant.now())) return hit;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://m.douban.com/")
                    .header("Accept", "image/jpeg,image/png,image/webp").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("Poster source unavailable");
                if (!java.util.Set.of("image/jpeg", "image/png", "image/webp").contains(type))
                    throw new IllegalStateException("Unsupported image type");
                bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
            }
            if (bytes.length > MAX_IMAGE_BYTES || !validImage(bytes, type)) throw new IllegalStateException("Invalid image");
            Poster poster = new Poster(bytes, type, Instant.now().plus(Duration.ofHours(12)));
            synchronized (cache) {
                Poster previous = cache.remove(url);
                if (previous != null) cacheBytes -= previous.bytes().length;
                while (!cache.isEmpty() && (cache.size() >= 64 || cacheBytes + bytes.length > MAX_CACHE_BYTES)) {
                    String oldest = cache.keySet().iterator().next();
                    cacheBytes -= cache.remove(oldest).bytes().length;
                }
                cache.put(url, poster); cacheBytes += bytes.length;
            }
            return poster;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "封面加载中断");
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "封面暂时无法加载");
        }
    }

    // Only an image URL already supplied by a loaded Douban chart can be fetched. No user URL proxy.
    static URI allowedPoster(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !uri.getHost().matches("img[0-9]+\\.doubanio\\.com")
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/view/photo/[a-z_]+/public/p[0-9]+\\.(jpg|png|webp)"))
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "封面地址无效");
        }
    }

    private static boolean validImage(byte[] bytes, String type) {
        if (bytes.length < 12) return false;
        return switch (type) {
            case "image/jpeg" -> (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "image/png" -> (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71;
            case "image/webp" -> new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
    }

    public record Poster(byte[] bytes, String contentType, Instant expiresAt) {}
}
