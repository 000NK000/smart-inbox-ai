package com.smartinbox.processor.controller;

import com.smartinbox.processor.entity.PracticeProgress;
import com.smartinbox.processor.service.PracticeService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping(value = "/api/practice", produces = MediaType.APPLICATION_JSON_VALUE)
public class PracticeController {
    private final PracticeService service;
    public PracticeController(PracticeService service) { this.service = service; }
    @GetMapping public List<PracticeProgress> list() { return service.list(); }
    @GetMapping("/groups") public List<com.smartinbox.processor.entity.PracticeGroup> groups() { return service.groups(); }
    @PostMapping("/groups") public com.smartinbox.processor.entity.PracticeGroup saveGroup(@RequestBody PracticeService.GroupInput input) { return service.saveGroup(input); }
    @PostMapping("/attempts") public PracticeProgress record(@RequestBody PracticeService.Attempt attempt) { return service.record(attempt); }
    @PatchMapping("/{number}/group") public PracticeProgress move(@PathVariable Integer number, @RequestBody PracticeService.MoveInput input) { return service.move(number, input); }
    @DeleteMapping("/{number}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer number, @RequestParam(required = false) Long version) { service.delete(number, version); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(failure.getReason(), "请求失败")));
    }
}
