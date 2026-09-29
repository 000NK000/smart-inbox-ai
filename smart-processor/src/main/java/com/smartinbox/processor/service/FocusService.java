package com.smartinbox.processor.service;

import com.smartinbox.processor.entity.AppPreference;
import com.smartinbox.processor.entity.FocusSession;
import com.smartinbox.processor.repository.AppPreferenceRepository;
import com.smartinbox.processor.repository.FocusSessionRepository;
import com.smartinbox.processor.repository.TaskItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class FocusService {
    private static final String LIMIT_KEY = "entertainmentDailyLimitMinutes";
    private static final Set<String> CATEGORIES = Set.of("JOB", "FRENCH", "COURSE", "ENTERTAINMENT");
    private final FocusSessionRepository sessions;
    private final AppPreferenceRepository preferences;
    private final TaskItemRepository tasks;

    public FocusService(FocusSessionRepository sessions, AppPreferenceRepository preferences, TaskItemRepository tasks) {
        this.sessions = sessions; this.preferences = preferences; this.tasks = tasks;
    }

    @Transactional
    public synchronized Overview overview(String zone) {
        ZoneId tz = zone(zone);
        long now = System.currentTimeMillis();
        int limit = limit();
        FocusSession active = reconcile(tz, now, limit);
        LocalDate today = Instant.ofEpochMilli(now).atZone(tz).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long dayStart = start(today, tz), dayEnd = start(today.plusDays(1), tz);
        long weekStart = start(monday, tz), weekEnd = start(monday.plusWeeks(1), tz);
        Map<String, Long> daily = emptyTotals(), weekly = emptyTotals(), taskMillis = new HashMap<>();
        Map<String, Map<String, Long>> weekDays = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) weekDays.put(monday.plusDays(i).toString(), emptyTotals());
        for (FocusSession session : sessions.overlapping(weekStart, weekEnd)) {
            long end = Math.min(now, session.getEndedAt() == null ? now : session.getEndedAt());
            if (end <= session.getStartedAt()) continue;
            long weekMs = overlap(session.getStartedAt(), end, weekStart, weekEnd);
            weekly.merge(session.getCategory(), weekMs, Long::sum);
            daily.merge(session.getCategory(), overlap(session.getStartedAt(), end, dayStart, dayEnd), Long::sum);
            if (session.getTaskId() != null) taskMillis.merge(session.getTaskId(), weekMs, Long::sum);
            for (var entry : weekDays.entrySet()) {
                LocalDate date = LocalDate.parse(entry.getKey());
                entry.getValue().merge(session.getCategory(), overlap(session.getStartedAt(), end,
                        start(date, tz), start(date.plusDays(1), tz)), Long::sum);
            }
        }
        List<TaskTime> taskTimes = taskMillis.entrySet().stream()
                .map(e -> new TaskTime(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(TaskTime::millis).reversed()).toList();
        return new Overview(now, today.toString(), monday.toString(), limit, daily, weekly, weekDays,
                active == null ? null : new Running(active.getId(), active.getCategory(), active.getTaskId(), active.getStartedAt()), taskTimes);
    }

    @Transactional
    public synchronized Overview start(String category, String taskId, String zone) {
        ZoneId tz = zone(zone);
        if (!CATEGORIES.contains(category)) throw bad("计时类别无效");
        long now = System.currentTimeMillis();
        int limit = limit();
        if (reconcile(tz, now, limit) != null) throw conflict("已有正在计时的类别，请先结束");
        if (taskId != null && !taskId.isBlank()) {
            if (category.equals("ENTERTAINMENT") || !tasks.existsById(taskId)) throw bad("关联的任务不存在");
        } else taskId = null;
        if (category.equals("ENTERTAINMENT") && entertainmentUsed(tz, now) >= limit * 60_000L)
            throw conflict("今天的娱乐时间已用完");
        FocusSession session = new FocusSession();
        session.setId(UUID.randomUUID().toString()); session.setCategory(category); session.setTaskId(taskId);
        session.setStartedAt(now);
        sessions.saveAndFlush(session);
        return overview(zone);
    }

    @Transactional
    public synchronized Overview stop(String id, String zone) {
        ZoneId tz = zone(zone);
        long now = System.currentTimeMillis();
        FocusSession active = reconcile(tz, now, limit());
        if (active == null || !active.getId().equals(id)) throw conflict("计时已结束，请刷新页面");
        long end = now;
        if (active.getCategory().equals("ENTERTAINMENT")) {
            long remaining = Math.max(0, limit() * 60_000L - entertainmentUsedBefore(active, tz));
            end = Math.min(end, active.getStartedAt() + remaining);
        }
        active.setEndedAt(Math.max(active.getStartedAt(), end));
        sessions.saveAndFlush(active);
        return overview(zone);
    }

    @Transactional
    public synchronized Overview setLimit(Integer minutes, String zone) {
        if (minutes == null || minutes < 1 || minutes > 1440) throw bad("娱乐额度须在 1 分钟至 24 小时之间");
        preferences.saveAndFlush(new AppPreference(LIMIT_KEY, Integer.toString(minutes)));
        return overview(zone);
    }

    private FocusSession reconcile(ZoneId tz, long now, int limit) {
        FocusSession active = sessions.findFirstByEndedAtIsNullOrderByStartedAtDesc().orElse(null);
        if (active == null || !active.getCategory().equals("ENTERTAINMENT")) return active;
        LocalDate began = Instant.ofEpochMilli(active.getStartedAt()).atZone(tz).toLocalDate();
        long midnight = start(began.plusDays(1), tz);
        long remaining = Math.max(0, limit * 60_000L - entertainmentUsedBefore(active, tz));
        long cutoff = Math.min(midnight, active.getStartedAt() + remaining);
        if (now < cutoff) return active;
        active.setEndedAt(Math.max(active.getStartedAt(), cutoff));
        sessions.saveAndFlush(active);
        return null;
    }

    private long entertainmentUsed(ZoneId tz, long now) {
        LocalDate day = Instant.ofEpochMilli(now).atZone(tz).toLocalDate();
        long from = start(day, tz), to = start(day.plusDays(1), tz);
        return sessions.overlapping(from, to).stream()
                .filter(s -> s.getCategory().equals("ENTERTAINMENT") && s.getEndedAt() != null)
                .mapToLong(s -> overlap(s.getStartedAt(), s.getEndedAt(), from, to)).sum();
    }

    private long entertainmentUsedBefore(FocusSession current, ZoneId tz) {
        LocalDate day = Instant.ofEpochMilli(current.getStartedAt()).atZone(tz).toLocalDate();
        long from = start(day, tz), to = start(day.plusDays(1), tz);
        return sessions.overlapping(from, to).stream()
                .filter(s -> s.getCategory().equals("ENTERTAINMENT") && s.getEndedAt() != null && !s.getId().equals(current.getId()))
                .mapToLong(s -> overlap(s.getStartedAt(), s.getEndedAt(), from, to)).sum();
    }

    private int limit() {
        try { return preferences.findById(LIMIT_KEY).map(p -> Integer.parseInt(p.getValue())).filter(n -> n >= 1 && n <= 1440).orElse(300); }
        catch (NumberFormatException invalid) { return 300; }
    }
    private static Map<String, Long> emptyTotals() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String category : List.of("JOB", "FRENCH", "COURSE", "ENTERTAINMENT")) result.put(category, 0L);
        return result;
    }
    private static long overlap(long start, long end, long from, long to) { return Math.max(0, Math.min(end, to) - Math.max(start, from)); }
    private static long start(LocalDate date, ZoneId zone) { return date.atStartOfDay(zone).toInstant().toEpochMilli(); }
    private static ZoneId zone(String value) {
        try { return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value); }
        catch (DateTimeException invalid) { throw bad("时区无效"); }
    }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    public record Running(String id, String category, String taskId, long startedAt) { }
    public record TaskTime(String taskId, long millis) { }
    public record Overview(long serverNow, String today, String weekStart, int entertainmentLimitMinutes,
                           Map<String, Long> daily, Map<String, Long> weekly, Map<String, Map<String, Long>> weekDays,
                           Running active, List<TaskTime> taskTimes) { }
}
