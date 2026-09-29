package com.smartinbox.processor.controller;

import com.smartinbox.processor.entity.TaskItem;
import com.smartinbox.processor.service.TaskService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping(value = "/api/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
public class TaskController {
    private final TaskService tasks;
    public TaskController(TaskService tasks) { this.tasks = tasks; }
    @GetMapping public List<TaskItem> list() { return tasks.list(); }
    @GetMapping("/summary") public TaskService.Summary summary(@RequestParam(required = false) String zone) { return tasks.summary(zone); }
    @PostMapping public TaskItem create(@RequestBody TaskService.Input request) { return tasks.create(request); }
    @PutMapping("/{id}") public TaskItem update(@PathVariable String id, @RequestBody TaskService.Input request) { return tasks.update(id, request); }
    @PatchMapping("/{id}/completion") public TaskItem completion(@PathVariable String id, @RequestBody Completion request) {
        if (request.completed() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定是否完成");
        return tasks.completion(id, request.completed(), request.version());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam(required = false) Long version) { tasks.delete(id, version); }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteAll() { tasks.deleteAll(); }
    @PostMapping("/import") public List<TaskItem> importLegacy(@RequestBody List<TaskService.Input> requests) { return tasks.importTasks(requests, false); }
    @PutMapping("/replace") public List<TaskItem> replace(@RequestBody List<TaskService.Input> requests) { return tasks.importTasks(requests, true); }
    @PostMapping("/from-mail") public TaskItem fromMail(@RequestBody TaskService.Input request) {
        try { return tasks.fromMail(request); }
        catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException duplicate) {
            // The failed transaction has ended here; reread the winner of a concurrent conversion.
            return tasks.findConverted(request.sourceMailId(), request.sourceSuggestionId()).orElseThrow(() -> duplicate);
        }
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "请求失败")));
    }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<?> conflict(Exception error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "任务重复或已被其他页面更新，请刷新后重试")); }
    public record Completion(Boolean completed, Long version) { }
}
