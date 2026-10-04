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
    @PostMapping("/presence") public FocusService.PresenceState presence(@RequestBody Presence body) { return focus.presence(body.runtimeId(), body.zone()); }
    @PostMapping("/presence/stop") public FocusService.PresenceState stopPresence(@RequestBody Presence body) { return focus.stopPresence(body.runtimeId(), body.zone()); }
    @PostMapping("/switch") public FocusService.Overview switchCategory(@RequestBody Switch body) { return focus.switchCategory(body.id(), body.zone()); }
    @RequestMapping(value = {"/start", "/stop", "/limit"}, method = {RequestMethod.POST, RequestMethod.PUT})
    public void retired() { throw new ResponseStatusException(HttpStatus.GONE, "计时已改为有效 / 无效时间，请刷新软件"); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "请求失败")));
    }
    public record Presence(String runtimeId, String zone) { }
    public record Switch(String id, String zone) { }
}
