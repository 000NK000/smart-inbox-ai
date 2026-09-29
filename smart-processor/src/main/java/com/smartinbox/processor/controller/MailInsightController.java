package com.smartinbox.processor.controller;

import com.smartinbox.processor.mail.MailInsightService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/mails", produces = MediaType.APPLICATION_JSON_VALUE)
public class MailInsightController {
    private final MailInsightService insights;

    public MailInsightController(MailInsightService insights) { this.insights = insights; }

    @PostMapping("/{id}/analysis")
    public MailInsightService.Report analyze(@PathVariable Long id) { return insights.analyze(id); }
}
