package com.smartinbox.processor.controller;

import com.smartinbox.processor.service.PracticeSolutionService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/practice")
public class PracticeSolutionController {
    private final PracticeSolutionService service;
    public PracticeSolutionController(PracticeSolutionService service) { this.service = service; }

    @GetMapping("/solutions") public List<Integer> numbers() { return service.numbers(); }
    @GetMapping("/{number}/solution") public PracticeSolutionService.View get(@PathVariable Integer number) { return service.get(number); }
    @PutMapping("/{number}/solution") public PracticeSolutionService.View save(@PathVariable Integer number, @RequestBody PracticeSolutionService.Input input) { return service.save(number, input); }

    @GetMapping("/solution-images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        var image = service.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.data());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(failure.getReason(), "请求失败")));
    }
}
