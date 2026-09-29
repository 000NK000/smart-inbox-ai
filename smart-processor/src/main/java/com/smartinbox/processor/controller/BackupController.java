package com.smartinbox.processor.controller;
import com.smartinbox.processor.service.BackupService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/dashboard/backup")
public class BackupController {
    private final BackupService service;
    public BackupController(BackupService service){this.service=service;}
    @GetMapping public BackupService.Envelope export(){return service.export();}
    @PostMapping("/validate") public BackupService.Preview validate(@RequestBody BackupService.Envelope file){return service.validate(file);}
    @PostMapping("/restore") public BackupService.Preview restore(@RequestBody BackupService.Envelope file){return service.restore(file);}
}
