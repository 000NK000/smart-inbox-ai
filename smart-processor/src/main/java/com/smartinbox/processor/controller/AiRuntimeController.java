package com.smartinbox.processor.controller;

import com.smartinbox.processor.service.AiRequestScheduler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard/ai")
public class AiRuntimeController {
    private final AiRequestScheduler scheduler;
    public AiRuntimeController(AiRequestScheduler scheduler) { this.scheduler = scheduler; }
    @GetMapping("/status") public AiRequestScheduler.Status status() { return scheduler.status(); }
}
