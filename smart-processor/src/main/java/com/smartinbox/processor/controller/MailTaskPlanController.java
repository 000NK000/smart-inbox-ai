package com.smartinbox.processor.controller;

import com.smartinbox.processor.mail.MailTaskPlanService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/mails/task-plan", produces = "application/json")
public class MailTaskPlanController {
    private final MailTaskPlanService plans;
    public MailTaskPlanController(MailTaskPlanService plans) { this.plans = plans; }
    @GetMapping public org.springframework.http.ResponseEntity<MailTaskPlanService.Overview> overview(
            @RequestParam(required = false) Long sinceVersion) {
        var view = plans.overview();
        return sinceVersion != null && sinceVersion == view.version()
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(view);
    }
    @GetMapping("/progress") public MailTaskPlanService.Progress progress() { return plans.progress(); }
    @PostMapping("/refresh") public MailTaskPlanService.Overview refresh() { plans.refresh(); return plans.overview(); }
    @PostMapping("/retry") public MailTaskPlanService.Progress retry() { return plans.retryFailed(); }
}
