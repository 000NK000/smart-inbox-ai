package com.smartinbox.collector.controller;

import com.smartinbox.collector.service.SourceHealthService;
import com.smartinbox.collector.task.EmailCollectorTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/outlook/sources")
public class SourceHealthController {
    private final SourceHealthService health;
    private final EmailCollectorTask tasks;
    public SourceHealthController(SourceHealthService health, EmailCollectorTask tasks) { this.health = health; this.tasks = tasks; }
    @GetMapping public Map<String, Object> status() { return Map.of("channels", health.snapshots()); }
    @PostMapping("/{source}/retry")
    public ResponseEntity<?> retry(@PathVariable String source) {
        final String normalized;
        try { normalized = SourceHealthService.source(source); }
        catch (IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", "不支持的邮件渠道")); }
        boolean accepted = tasks.retry(normalized);
        return ResponseEntity.status(accepted ? 202 : 409).body(Map.of("accepted", accepted, "source", normalized,
                "message", accepted ? "已开始重试该渠道" : "该渠道正在同步，请稍后查看状态"));
    }
}
