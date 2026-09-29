package com.smartinbox.collector.controller;

import com.smartinbox.collector.service.impl.OutlookGraphService;
import com.smartinbox.collector.service.impl.OutlookOAuthService;
import com.smartinbox.collector.service.impl.OutlookDesktopService;
import com.smartinbox.collector.task.EmailCollectorTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/outlook")
public class OutlookController {
    private final OutlookOAuthService oauth;
    private final OutlookGraphService graph;
    private final OutlookDesktopService desktop;
    private final EmailCollectorTask tasks;

    public OutlookController(OutlookOAuthService oauth, OutlookGraphService graph, OutlookDesktopService desktop, EmailCollectorTask tasks) {
        this.oauth = oauth;
        this.graph = graph;
        this.desktop = desktop;
        this.tasks = tasks;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(oauth.status());
        Map<String, Object> desktopStatus = desktop.status();
        result.put("desktopState", desktopStatus.get("state"));
        result.put("desktopAvailable", desktopStatus.get("available"));
        result.put("sync", desktopStatus);
        if (!Boolean.TRUE.equals(result.get("connected")) && "connected".equals(desktopStatus.get("state"))) {
            result.put("connected", true);
            result.put("mode", "desktop");
        } else if (Boolean.TRUE.equals(result.get("connected"))) {
            result.put("mode", "oauth");
        }
        return result;
    }

    @PostMapping("/device/start")
    public ResponseEntity<?> start() {
        try { return ResponseEntity.ok(oauth.startDeviceAuthorization()); }
        catch (Exception exception) { return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage())); }
    }

    @GetMapping("/device/poll")
    public ResponseEntity<?> poll(@RequestParam String sessionId) {
        try { return ResponseEntity.ok(oauth.pollDeviceAuthorization(sessionId)); }
        catch (Exception exception) { return ResponseEntity.badRequest().body(Map.of("state", "failed", "message", exception.getMessage())); }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        boolean success = tasks.syncSource("OUTLOOK");
        Map<String, Object> result = new java.util.LinkedHashMap<>(status());
        result.put("success", success);
        return result;
    }

    @PostMapping("/desktop/refresh")
    public Map<String, Object> refreshDesktop() {
        tasks.syncDesktop();
        return desktop.status();
    }
}
