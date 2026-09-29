package com.smartinbox.processor.controller;

import com.smartinbox.processor.trend.TrendPlatform;
import com.smartinbox.processor.trend.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard/trends")
public class TrendController {

    private final TrendService trendService;

    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

    @GetMapping
    public List<TrendPlatform> overview(@RequestParam(defaultValue = "false") boolean refresh) {
        return trendService.overview(refresh);
    }
}
