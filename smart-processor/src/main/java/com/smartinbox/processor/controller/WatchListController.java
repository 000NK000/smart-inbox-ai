package com.smartinbox.processor.controller;

import com.smartinbox.processor.entity.WatchListEntry;
import com.smartinbox.processor.repository.WatchListRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.text.Normalizer;
import java.util.*;

@RestController
@RequestMapping(value = "/api/dashboard/watchlist", produces = MediaType.APPLICATION_JSON_VALUE)
public class WatchListController {
    private final WatchListRepository repository;
    public WatchListController(WatchListRepository repository) { this.repository = repository; }

    @GetMapping
    public List<WatchListEntry> list() { return repository.findAllByOrderByUpdatedAtDesc(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WatchListEntry create(@RequestBody WatchRequest request) {
        var entry = new WatchListEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setCreatedAt(System.currentTimeMillis());
        apply(entry, request);
        return repository.saveAndFlush(entry);
    }

    @PutMapping("/{id}")
    @Transactional
    public WatchListEntry update(@PathVariable String id, @RequestBody WatchRequest request) {
        var entry = find(id);
        if (request.version() == null || !request.version().equals(entry.getVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这条记录已在其他页面更新，请刷新后再修改");
        apply(entry, request);
        return repository.saveAndFlush(entry);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable String id, @RequestParam Long version) {
        var entry = find(id);
        if (!version.equals(entry.getVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这条记录已更新，请刷新后再删除");
        repository.delete(entry);
        repository.flush();
    }

    private WatchListEntry find(String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这条追剧记录已不存在"));
    }

    private void apply(WatchListEntry entry, WatchRequest request) {
        String title = bounded(request.title(), 300, "片名");
        if (title.isBlank()) throw bad("请填写片名");
        String kind = request.kind() == null ? "TV" : request.kind();
        String status = request.status() == null ? "PLANNED" : request.status();
        if (!Set.of("MOVIE", "TV", "VARIETY").contains(kind)) throw bad("请选择电影、电视剧或综艺");
        if (!Set.of("PLANNED", "WATCHING", "COMPLETED").contains(status)) throw bad("观看状态无效");
        int current = request.currentEpisode() == null ? 0 : request.currentEpisode();
        Integer total = request.totalEpisodes();
        if (current < 0 || current > 100000 || (total != null && (total < 1 || total > 100000 || current > total)))
            throw bad("集数无效，已看集数不能超过总集数");
        if (kind.equals("MOVIE")) { current = 0; total = null; }
        String url = bounded(request.url(), 2048, "链接");
        if (!url.isBlank()) {
            try {
                URI uri = URI.create(url);
                if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))
                        || uri.getHost() == null || uri.getUserInfo() != null) throw bad("链接必须是完整的 http 或 https 地址");
            } catch (IllegalArgumentException exception) { throw bad("链接格式不正确"); }
        }
        String key = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (key.length() > 300) throw bad("片名过长");
        repository.findByKindAndTitleKey(kind, key).filter(other -> !other.getId().equals(entry.getId())).ifPresent(other -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这部作品已经在清单里了");
        });
        entry.setTitle(title); entry.setTitleKey(key); entry.setKind(kind); entry.setStatus(status);
        entry.setCurrentEpisode(current); entry.setTotalEpisodes(total);
        entry.setNotes(bounded(request.notes(), 2000, "备注"));
        entry.setUrl(url); entry.setSource(bounded(request.source(), 60, "来源"));
        entry.setUpdatedAt(System.currentTimeMillis());
    }

    private String bounded(String value, int max, String field) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) throw bad(field + "过长");
        return clean;
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "请求失败")));
    }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<?> conflict(Exception error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "记录重复或已被其他页面更新，请刷新后重试"));
    }

    public record WatchRequest(String title, String kind, String status, Integer currentEpisode,
                               Integer totalEpisodes, String notes, String url, String source, Long version) {}
}
