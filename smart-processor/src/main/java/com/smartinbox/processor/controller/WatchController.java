package com.smartinbox.processor.controller;

import com.smartinbox.processor.watch.WatchOverview;
import com.smartinbox.processor.watch.WatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/watch")
public class WatchController {

    private final WatchService watchService;

    public WatchController(WatchService watchService) {
        this.watchService = watchService;
    }

    @GetMapping
    public WatchOverview overview(@RequestParam(defaultValue = "false") boolean refresh) {
        return watchService.overview(refresh);
    }
}
