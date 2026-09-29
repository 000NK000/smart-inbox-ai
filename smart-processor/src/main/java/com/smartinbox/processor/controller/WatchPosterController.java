package com.smartinbox.processor.controller;

import com.smartinbox.processor.watch.WatchPosterService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
@RequestMapping("/api/dashboard/watch/douban/poster")
public class WatchPosterController {
    private final WatchPosterService posters;
    public WatchPosterController(WatchPosterService posters) { this.posters = posters; }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> poster(@PathVariable String id) {
        var poster = posters.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(poster.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(poster.bytes());
    }
}
