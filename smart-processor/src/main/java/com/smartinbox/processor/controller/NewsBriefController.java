package com.smartinbox.processor.controller;

import com.smartinbox.processor.news.NewsBriefService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dashboard/us-news")
public class NewsBriefController {
    private final DashboardController feeds;
    private final NewsBriefService briefs;
    public NewsBriefController(DashboardController feeds, NewsBriefService briefs) {
        this.feeds = feeds;
        this.briefs = briefs;
    }

    @PostMapping(value = "/brief", produces = "application/json")
    public NewsBriefService.Brief brief(@RequestBody BriefRequest request) {
        if (request == null || !NewsBriefService.supportedSource(request.source()) || request.url() == null
                || request.url().isBlank() || request.url().length() > 2048)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid news selection");
        var item = feeds.findUsNewsItem(request.source(), request.url())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Please refresh the news list"));
        try { return briefs.summarize(item); }
        catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chinese news brief is temporarily unavailable", error);
        }
    }

    public record BriefRequest(String source, String url) { }
}
