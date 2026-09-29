package com.smartinbox.processor.controller;

import com.smartinbox.processor.service.FocusService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping(value = "/api/focus", produces = MediaType.APPLICATION_JSON_VALUE)
public class FocusController {
    private final FocusService focus;
    public FocusController(FocusService focus) { this.focus = focus; }
    @GetMapping public FocusService.Overview overview(@RequestParam(required = false) String zone) { return focus.overview(zone); }
    @PostMapping("/start") public FocusService.Overview start(@RequestBody Start body) { return focus.start(body.category(), body.taskId(), body.zone()); }
    @PostMapping("/stop") public FocusService.Overview stop(@RequestBody Stop body) { return focus.stop(body.id(), body.zone()); }
    @PutMapping("/limit") public FocusService.Overview limit(@RequestBody Limit body) { return focus.setLimit(body.minutes(), body.zone()); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "请求失败")));
    }
    public record Start(String category, String taskId, String zone) { }
    public record Stop(String id, String zone) { }
    public record Limit(Integer minutes, String zone) { }
}
