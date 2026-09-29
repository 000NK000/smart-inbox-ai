package com.smartinbox.processor.controller;

import com.smartinbox.processor.job.JobApplicationService;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping(value="/api/job-applications",produces=MediaType.APPLICATION_JSON_VALUE)
public class JobApplicationController {
    private final JobApplicationService service;
    public JobApplicationController(JobApplicationService service){this.service=service;}
    @GetMapping public JobApplicationService.Overview overview(){return service.overview();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public JobApplicationService.ApplicationView create(@RequestBody JobApplicationService.Input input){return service.create(input);}
    @PutMapping("/{id}") public JobApplicationService.ApplicationView update(@PathVariable String id,@RequestBody JobApplicationService.Input input){return service.update(id,input);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id,@RequestParam(required=false)Long version){service.delete(id,version);}
    @PutMapping("/{id}/mails/{mailId}") public JobApplicationService.MoveMailResult moveMail(@PathVariable String id,@PathVariable Long mailId,@RequestBody JobApplicationService.MailLinkInput input){return service.moveMail(id,mailId,input);}
    @DeleteMapping("/{id}/mails/{mailId}") public JobApplicationService.ApplicationView unlinkMail(@PathVariable String id,@PathVariable Long mailId,@RequestParam(required=false)Long version){return service.unlinkMail(id,mailId,version);}
    @PostMapping("/analysis") @ResponseStatus(HttpStatus.ACCEPTED) public JobApplicationService.Progress analyze(){return service.analyzeNewMail();}
    @PostMapping("/suggestions/{mailId}/apply") public JobApplicationService.ApplyResult apply(@PathVariable Long mailId,@RequestBody JobApplicationService.ApplyInput input){return service.applySuggestion(mailId,input);}
    @PostMapping("/suggestions/{mailId}/dismiss") @ResponseStatus(HttpStatus.NO_CONTENT) public void dismiss(@PathVariable Long mailId,@RequestParam(required=false)Long version){service.dismissSuggestion(mailId,version);}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> requestError(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(e.getReason(),"请求失败")));}
    @ExceptionHandler({OptimisticLockingFailureException.class,OptimisticLockException.class}) public ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","申请记录或邮件建议已变化，请刷新后重试"));}
}
