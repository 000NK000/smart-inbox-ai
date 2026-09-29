package com.smartinbox.processor.controller;

import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.mail.MailQueryService;
import com.smartinbox.processor.mail.MailReadStateChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@RestController @RequestMapping("/api/mails")
public class MailSummaryController {
    private final MailSummaryRepository repository;
    private final MailQueryService query;
    private final ApplicationEventPublisher events;
    public MailSummaryController(MailSummaryRepository repository, MailQueryService query, ApplicationEventPublisher events) {
        this.repository=repository; this.query=query; this.events=events;
    }
    @GetMapping("/summaries")
    public Page<MailQueryService.Item> getSummaries(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String tab,
            @RequestParam(required=false) String source, @RequestParam(required=false) String category,
            @RequestParam(required=false) Integer urgency, @RequestParam(required=false) String view,
            @RequestParam(defaultValue="false") boolean excludeAd, @RequestParam(required=false) String q,
            @RequestParam(required=false) Integer hours, @RequestParam(required=false) Boolean starred) {
        if(page<0 || page>100000 || size<1 || size>100) throw bad("分页参数无效");
        return query.list(filter(tab,source,category,urgency,view,excludeAd,q,hours,starred),page,size);
    }
    public Page<MailQueryService.Item> getSummaries(int page,int size,String tab,String source,String category,Integer urgency,String view,boolean excludeAd) {
        return getSummaries(page,size,tab,source,category,urgency,view,excludeAd,null,null,null);
    }
    @GetMapping("/revision")
    public MailQueryService.Revision revision(@RequestParam(required=false) String tab,@RequestParam(required=false) String source,
            @RequestParam(required=false) String category,@RequestParam(required=false) Integer urgency,@RequestParam(required=false) String view,
            @RequestParam(defaultValue="false") boolean excludeAd,@RequestParam(required=false) String q,
            @RequestParam(required=false) Integer hours,@RequestParam(required=false) Boolean starred) {
        return query.revision(filter(tab,source,category,urgency,view,excludeAd,q,hours,starred));
    }
    private MailQueryService.Filter filter(String tab,String source,String category,Integer urgency,String view,boolean excludeAd,String q,Integer hours,Boolean starred) {
        if(q!=null && q.length()>200) throw bad("搜索词最多200字");
        if(hours!=null && (hours<1 || hours>120)) throw bad("搜索范围为最近1到120小时；星标邮件不受时间限制");
        if(view!=null && !Set.of("INBOX","STARRED","SNOOZED","ALL").contains(view.toUpperCase(Locale.ROOT))) throw bad("邮件视图无效");
        return new MailQueryService.Filter(tab,source,category,urgency,view,excludeAd,q,hours,starred);
    }
    @GetMapping("/sync-index")
    public Map<String,Object> syncIndex(@RequestParam String source) { return Map.of("complete",true,"items",query.syncIndex(source)); }
    @GetMapping("/body-backfill")
    public List<String> bodyBackfill(@RequestParam String source) { return query.syncIndex(source).stream().filter(i->!Boolean.TRUE.equals(i.bodyAvailable())).map(MailQueryService.SyncItem::externalId).toList(); }
    @GetMapping("/reminders") public List<Map<String,Object>> reminders() { return query.reminders(); }
    @PostMapping("/{id}/reminder-ack") @Transactional
    public void acknowledge(@PathVariable Long id,@RequestBody SnoozeRequest request) {
        if(request.until()!=null) repository.acknowledgeReminder(id,request.until(),System.currentTimeMillis(),LocalDateTime.now());
    }
    @PatchMapping("/{id}/snooze") @Transactional
    public MailQueryService.Item snooze(@PathVariable Long id,@RequestBody SnoozeRequest request) {
        if(request.until()!=null && (request.until()<=System.currentTimeMillis() || request.until()>System.currentTimeMillis()+366L*86400000)) throw bad("请选择未来一年以内的提醒时间");
        var mail=find(id); mail.setSnoozedUntil(request.until()); mail.setReminderNotifiedAt(null);
        mail.setInboxRead(false);
        var saved = repository.save(mail);
        events.publishEvent(new MailReadStateChanged(id, false));
        return item(saved);
    }
    @GetMapping("/{id}") public MailDetail getMail(@PathVariable Long id) {
        var m=find(id);
        return new MailDetail(m.getId(),m.getExternalId(),m.getSubject(),m.getOriginalSubject(),m.getSender(),m.getCategory(),m.getSummary(),m.getContent(),m.getHtmlContent(),m.getUrgency(),m.getAction(),m.getStatus(),m.getCreatedTime(),m.getSource(),m.isInboxRead(),m.isStarred(),m.getSnoozedUntil());
    }
    @PatchMapping("/{id}/read") @Transactional
    public MailQueryService.Item updateReadState(@PathVariable Long id,@RequestBody(required=false) MailStateRequest request) {
        var mail=find(id); mail.setInboxRead(request==null || request.value()==null || request.value());
        if(mail.isInboxRead()) { mail.setSnoozedUntil(null); mail.setReminderNotifiedAt(null); }
        var saved = repository.save(mail);
        events.publishEvent(new MailReadStateChanged(id, mail.isInboxRead()));
        return item(saved);
    }
    @PatchMapping("/{id}/star") @Transactional
    public MailQueryService.Item updateStarState(@PathVariable Long id,@RequestBody(required=false) MailStateRequest request) {
        var mail=find(id); mail.setStarred(request!=null && request.value()!=null ? request.value() : !mail.isStarred()); return item(repository.save(mail));
    }
    private MailSummary find(Long id) { return repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"邮件不存在")); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private MailQueryService.Item item(MailSummary m) { return new MailQueryService.Item(m.getId(),m.getExternalId(),m.getSubject(),m.getSender(),m.getCategory(),m.getSummary(),m.getUrgency(),m.getAction(),m.getStatus(),m.getCreatedTime(),m.getSource(),m.isInboxRead(),m.isStarred(),m.getSnoozedUntil()); }
    public record MailStateRequest(Boolean value) {}
    public record SnoozeRequest(Long until) {}
    public record MailDetail(Long id,String externalId,String subject,String originalSubject,String sender,String category,String summary,String content,String htmlContent,Integer urgency,String action,String status,LocalDateTime createdTime,String source,boolean inboxRead,boolean starred,Long snoozedUntil) {}
}
