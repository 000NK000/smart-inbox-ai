package com.smartinbox.processor.controller;

import com.smartinbox.processor.service.CalendarService;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping(value = "/api/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
public class CalendarController {
    private final CalendarService calendar;
    public CalendarController(CalendarService calendar) { this.calendar = calendar; }
    @GetMapping
    public CalendarService.CalendarView list(@RequestParam String from, @RequestParam String to, @RequestParam String zone) {
        return calendar.list(from, to, zone);
    }
    @PostMapping("/events") @ResponseStatus(HttpStatus.CREATED)
    public CalendarService.Definition create(@RequestBody CalendarService.Input request) { return calendar.create(request); }
    @PutMapping("/events/{id}")
    public CalendarService.Definition update(@PathVariable String id, @RequestBody CalendarService.Input request) { return calendar.update(id, request); }
    @DeleteMapping("/events/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam(required = false) Long version) { calendar.delete(id, version); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "请求失败")));
    }
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<?> conflict(Exception error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "日程已被其他页面更新或删除，请刷新后重试"));
    }
}
