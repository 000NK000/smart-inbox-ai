package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.TaskItem;
import com.smartinbox.processor.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskService {
    private static final long MAX_DATE = 4102444800000L;
    private final TaskItemRepository tasks;
    private final MailSummaryRepository mails;
    private final MailTaskAnalysisRepository analyses;
    private final ObjectMapper mapper;
    @PersistenceContext private EntityManager entityManager;
    private final AtomicLong revisionClock = new AtomicLong(System.currentTimeMillis());
    public TaskService(TaskItemRepository tasks, MailSummaryRepository mails, MailTaskAnalysisRepository analyses, ObjectMapper mapper) {
        this.tasks = tasks; this.mails = mails; this.analyses = analyses; this.mapper = mapper;
    }
    public List<TaskItem> list() { return tasks.findAllByOrderByCreatedAtDesc(); }
    public Summary summary(String zone) {
        ZoneId zoneId;
        try { zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone); }
        catch (DateTimeException error) { throw bad("时区无效"); }
        long now = System.currentTimeMillis();
        var today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate();
        long start = today.atStartOfDay(zoneId).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli();
        var rows = tasks.findCounterRows();
        long open = 0, completed = 0, dueToday = 0, overdue = 0, upcoming = 0, noDeadline = 0;
        StringBuilder revision = new StringBuilder();
        for (var row : rows) {
            revision.append(row.getId()).append(':').append(row.getVersion()).append(':').append(row.getUpdatedAt()).append(';');
            if ("COMPLETED".equals(row.getStatus())) { completed++; continue; }
            open++;
            Long due = row.getDueAt();
            if (due == null) noDeadline++;
            else {
                if (due < now) overdue++;
                if (due >= start && due < end) dueToday++;
                if (due >= end) upcoming++;
            }
        }
        return new Summary(hash(revision.toString()), rows.size(), open, completed, dueToday, overdue, upcoming, noDeadline);
    }
    @Transactional public TaskItem create(Input input) {
        TaskItem task = validated(input);
        if (tasks.existsById(task.getId())) throw conflict();
        if (task.getSourceMailId() != null) throw bad("请通过邮件建议入口关联来源");
        return tasks.saveAndFlush(task);
    }
    @Transactional public TaskItem update(String id, Input input) {
        if (input == null) throw bad("任务不能为空");
        TaskItem task = find(id); checkVersion(task, input.version());
        task.setText(text(input.text(), 300, true));
        if (input.priority() != null) task.setPriority(priority(input.priority()));
        Long nextDue = date(input.dueAt());
        if (task.getDueAt() != null && nextDue != null && nextDue > task.getDueAt())
            task.setRescheduleCount(task.getRescheduleCount() + 1);
        task.setDueAt(nextDue);
        if (input.notes() != null) task.setNotes(text(input.notes(), 2000, false));
        task.setUpdatedAt(nextUpdate());
        return tasks.saveAndFlush(task);
    }
    @Transactional public TaskItem completion(String id, boolean completed, Long version) {
        TaskItem task = find(id); checkVersion(task, version);
        String next = completed ? "COMPLETED" : "OPEN";
        if (!next.equals(task.getStatus())) {
            task.setStatus(next); task.setCompletedAt(completed ? System.currentTimeMillis() : null);
            task.setUpdatedAt(nextUpdate());
        }
        return tasks.saveAndFlush(task);
    }
    @Transactional public void delete(String id, Long version) { var task = find(id); checkVersion(task, version); tasks.delete(task); tasks.flush(); }
    @Transactional public void deleteAll() { tasks.deleteAll(); }

    // Complete validation before deletion; a replacement is one database transaction.
    @Transactional public List<TaskItem> importTasks(List<Input> input, boolean replace) {
        var validated = validateBatch(input);
        if (replace) { tasks.deleteAllInBatch(); entityManager.clear(); }
        for (var task : validated) {
            if (!replace && (tasks.existsById(task.getId()) || (task.getSourceMailId() != null &&
                    tasks.findBySourceMailIdAndSourceSuggestionId(task.getSourceMailId(), task.getSourceSuggestionId()).isPresent()))) continue;
            tasks.save(task);
        }
        tasks.flush(); return list();
    }
    public List<TaskItem> validateBatch(List<Input> input) {
        if (input == null || input.size() > 10000) throw bad("任务备份最多包含 10000 条记录");
        Set<String> ids = new HashSet<>(), sources = new HashSet<>();
        List<TaskItem> result = new ArrayList<>();
        for (Input row : input) {
            var task = validated(row);
            if (!ids.add(task.getId())) throw bad("任务编号重复");
            if (task.getSourceMailId() != null && !sources.add(task.getSourceMailId() + ":" + task.getSourceSuggestionId())) throw bad("邮件建议重复");
            result.add(task);
        }
        return result;
    }
    @Transactional public TaskItem fromMail(Input input) {
        if (input == null || input.sourceMailId() == null || input.sourceSuggestionId() == null) throw bad("缺少来源邮件或建议");
        var existing = tasks.findBySourceMailIdAndSourceSuggestionId(input.sourceMailId(), input.sourceSuggestionId());
        if (existing.isPresent()) return existing.get();
        if (!mails.existsById(input.sourceMailId())) throw bad("来源邮件不存在");
        var analysis = analyses.findById(input.sourceMailId()).orElseThrow(() -> bad("邮件建议不存在，请重新分析"));
        boolean known = false;
        try {
            for (var suggestion : mapper.readTree(analysis.getTasksJson()))
                if (input.sourceSuggestionId().equals(suggestion.path("id").asText())) { known = true; break; }
        } catch (Exception error) { throw bad("邮件建议暂时不可用"); }
        if (!known) throw bad("邮件建议已变化，请刷新后再试");
        // The business unique key provides idempotency; a derived primary key could collide
        // with an imported/manual id and cause JPA merge to overwrite an unrelated task.
        var task = validated(new Input(UUID.randomUUID().toString(), input.text(), null,
                input.priority(), input.dueAt(), "OPEN", null, null, input.notes(), input.sourceMailId(), input.sourceSuggestionId(), null));
        return tasks.saveAndFlush(task);
    }
    public Optional<TaskItem> findConverted(Long mailId, String suggestionId) { return tasks.findBySourceMailIdAndSourceSuggestionId(mailId, suggestionId); }
    /** Internal conversion for a server-validated recruitment-mail suggestion. */
    @Transactional public TaskItem fromJobSuggestion(Long mailId, String suggestionId, String title,
                                                       String priority, String notes) {
        if (mailId == null || suggestionId == null || !suggestionId.startsWith("job:") || !mails.existsById(mailId))
            throw bad("招聘邮件来源无效");
        var existing = tasks.findBySourceMailIdAndSourceSuggestionId(mailId, suggestionId);
        if (existing.isPresent()) return existing.get();
        var task = validated(new Input(UUID.randomUUID().toString(), title, null, priority, null,
                "OPEN", null, null, notes, mailId, suggestionId, null));
        return tasks.saveAndFlush(task);
    }
    private TaskItem validated(Input input) {
        if (input == null) throw bad("任务不能为空");
        TaskItem task = new TaskItem();
        String id = input.id() == null || input.id().isBlank() ? UUID.randomUUID().toString() : input.id().trim();
        if (id.length() > 80 || !id.matches("[A-Za-z0-9_.:-]+")) throw bad("任务编号无效");
        task.setId(id); task.setText(text(input.text(), 300, true));
        task.setCreatedAt(input.createdAt() == null ? System.currentTimeMillis() : date(input.createdAt()));
        // Restoration changes the current snapshot even when ids and optimistic versions match.
        date(input.updatedAt());
        task.setUpdatedAt(nextUpdate());
        task.setPriority(priority(input.priority())); task.setDueAt(date(input.dueAt()));
        String status = input.status() == null ? "OPEN" : input.status();
        if (!Set.of("OPEN", "COMPLETED").contains(status)) throw bad("任务状态无效");
        task.setStatus(status); task.setCompletedAt("COMPLETED".equals(status) ?
                (input.completedAt() == null ? System.currentTimeMillis() : date(input.completedAt())) : null);
        task.setNotes(text(input.notes(), 2000, false));
        String suggestion = input.sourceSuggestionId();
        if ((input.sourceMailId() == null) != (suggestion == null)) throw bad("邮件来源信息不完整");
        if (input.sourceMailId() != null && (input.sourceMailId() < 1 || suggestion.isBlank() || suggestion.length() > 80)) throw bad("邮件来源无效");
        task.setSourceMailId(input.sourceMailId()); task.setSourceSuggestionId(suggestion);
        // Imported snapshots never control a new optimistic-lock version.
        task.setVersion(0L); return task;
    }
    private TaskItem find(String id) { return tasks.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")); }
    private long nextUpdate() { return revisionClock.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis())); }
    private void checkVersion(TaskItem task, Long version) { if (version != null && !version.equals(task.getVersion())) throw conflict(); }
    private String priority(String value) { String result = value == null ? "NORMAL" : value; if (!Set.of("HIGH", "NORMAL", "LOW").contains(result)) throw bad("优先级无效"); return result; }
    private Long date(Long value) { if (value != null && (value < 0 || value > MAX_DATE)) throw bad("日期应在 1970 至 2100 年之间"); return value; }
    private String text(String value, int limit, boolean required) { String result = value == null ? "" : value.trim(); if ((required && result.isEmpty()) || result.length() > limit) throw bad("内容为空或超过长度限制 " + limit); return result; }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "任务已变化，请刷新后再修改"); }
    public record Input(String id, String text, Long createdAt, String priority, Long dueAt, String status, Long completedAt,
                        Long updatedAt, String notes, Long sourceMailId, String sourceSuggestionId, Long version) { }
    public record Summary(String version, long total, long open, long completed, long dueToday, long overdue, long upcoming, long noDeadline) { }
}
