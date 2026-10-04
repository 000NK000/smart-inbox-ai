package com.smartinbox.processor.service;

import com.smartinbox.processor.entity.FocusSession;
import com.smartinbox.processor.repository.FocusSessionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Supplier;

/** A single desktop-owned timer. The runtime confirms its presence every 15 seconds. */
@Service
public class FocusService {
    public static final long LEASE_MILLIS = 45_000L;
    public static final String EFFECTIVE = "EFFECTIVE", INEFFECTIVE = "INEFFECTIVE";
    private final FocusSessionRepository sessions;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public FocusService(FocusSessionRepository sessions, PlatformTransactionManager manager, ObjectProvider<Clock> clocks) {
        this.sessions = sessions;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        this.transaction = new TransactionTemplate(manager);
        // Commit before releasing this single local processor's timer lock, even with an outer transaction.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private synchronized <T> T atomic(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    public Overview overview(String zone) {
        ZoneId tz = zone(zone);
        return atomic(() -> {
            long now = clock.millis();
            return overview(tz, now, reconcile(now));
        });
    }

    public PresenceState presence(String runtimeId, String zone) {
        validateRuntime(runtimeId);
        zone(zone);
        return atomic(() -> {
            long now = clock.millis();
            FocusSession active = reconcile(now);
            if (active != null && !runtimeId.equals(active.getRuntimeId())) {
                // A new desktop run must not inherit the former run's effective mode or offline gap.
                close(active, confirmedEnd(active, now));
                active = null;
            }
            if (active == null) active = begin(INEFFECTIVE, runtimeId, now, now);
            else active.setLastHeartbeatAt(now);
            sessions.flush();
            return new PresenceState(now, running(active));
        });
    }

    public PresenceState stopPresence(String runtimeId, String zone) {
        validateRuntime(runtimeId);
        zone(zone);
        return atomic(() -> {
            long now = clock.millis();
            FocusSession active = reconcile(now);
            if (active != null && runtimeId.equals(active.getRuntimeId())) {
                close(active, now);
                active = null;
            }
            sessions.flush();
            return new PresenceState(now, running(active));
        });
    }

    public Overview switchCategory(String expectedId, String zone) {
        if (expectedId == null || expectedId.isBlank() || expectedId.length() > 36) throw bad("计时记录无效，请刷新页面");
        ZoneId tz = zone(zone);
        return atomic(() -> {
            long now = clock.millis();
            FocusSession active = reconcile(now);
            if (active == null || !active.getId().equals(expectedId))
                throw conflict("计时状态已变化，请刷新后重试");
            String next = EFFECTIVE.equals(active.getCategory()) ? INEFFECTIVE : EFFECTIVE;
            String runtimeId = active.getRuntimeId();
            long heartbeat = active.getLastHeartbeatAt();
            close(active, now);
            // Switching from another screen/device is not proof that the desktop is still awake.
            FocusSession replacement = begin(next, runtimeId, now, heartbeat);
            sessions.flush();
            return overview(tz, now, replacement);
        });
    }

    private FocusSession begin(String category, String runtimeId, long startedAt, long heartbeat) {
        FocusSession result = new FocusSession();
        result.setId(UUID.randomUUID().toString()); result.setCategory(category); result.setStartedAt(startedAt);
        result.setRuntimeId(runtimeId); result.setLastHeartbeatAt(heartbeat);
        return sessions.save(result);
    }
    private void close(FocusSession session, long end) {
        session.setEndedAt(Math.max(session.getStartedAt(), end));
        sessions.save(session);
    }
    private FocusSession reconcile(long now) {
        FocusSession current = null;
        for (FocusSession session : sessions.findByEndedAtIsNullOrderByStartedAtDesc()) {
            boolean valid = session.getRuntimeId() != null && session.getLastHeartbeatAt() != null
                    && (EFFECTIVE.equals(session.getCategory()) || INEFFECTIVE.equals(session.getCategory()));
            if (!valid || session.getLastHeartbeatAt() > now || now > leaseUntil(session) || current != null) {
                // Old four-category active records have no presence evidence. Never count offline time.
                close(session, confirmedEnd(session, now));
            } else current = session;
        }
        sessions.flush();
        return current;
    }

    /** Safe endpoint for active records when exporting a closed backup snapshot. */
    public static long accountedEnd(FocusSession session, long now) {
        if (session.getEndedAt() != null) return Math.max(session.getStartedAt(), Math.min(now, session.getEndedAt()));
        if (session.getLastHeartbeatAt() == null || session.getRuntimeId() == null
                || session.getLastHeartbeatAt() > now || now > leaseUntil(session)) return confirmedEnd(session, now);
        return Math.max(session.getStartedAt(), now);
    }
    private static long confirmedEnd(FocusSession session, long now) {
        long confirmed = session.getLastHeartbeatAt() == null ? session.getStartedAt() : session.getLastHeartbeatAt();
        return Math.max(session.getStartedAt(), Math.min(now, confirmed));
    }
    private static long leaseUntil(FocusSession session) {
        return session.getLastHeartbeatAt() == null ? session.getStartedAt() : session.getLastHeartbeatAt() + LEASE_MILLIS;
    }
    private static Running running(FocusSession active) {
        return active == null ? null : new Running(active.getId(), active.getCategory(), active.getStartedAt(), leaseUntil(active));
    }

    private Overview overview(ZoneId tz, long now, FocusSession active) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(tz).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate historyStart = today.minusMonths(3);
        Map<LocalDate, Map<String, Long>> totals = new LinkedHashMap<>();
        for (LocalDate date = historyStart; !date.isAfter(today); date = date.plusDays(1)) totals.put(date, emptyTotals());
        long from = start(historyStart, tz), to = start(today.plusDays(1), tz);
        for (FocusSession session : sessions.overlapping(from, to)) {
            String category = mappedCategory(session.getCategory());
            if (category == null) continue;
            long end = accountedEnd(session, now);
            if (end <= session.getStartedAt()) continue;
            LocalDate first = Instant.ofEpochMilli(Math.max(from, session.getStartedAt())).atZone(tz).toLocalDate();
            LocalDate last = Instant.ofEpochMilli(Math.min(to, end) - 1).atZone(tz).toLocalDate();
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                Map<String, Long> day = totals.get(date);
                if (day != null) day.merge(category, overlap(session.getStartedAt(), end, start(date, tz), start(date.plusDays(1), tz)), Long::sum);
            }
        }
        Map<String, Long> weekly = emptyTotals();
        List<DayTotal> weekDays = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = monday.plusDays(day);
            Map<String, Long> dayTotals = totals.getOrDefault(date, emptyTotals());
            dayTotals.forEach((category, millis) -> weekly.merge(category, millis, Long::sum));
            weekDays.add(new DayTotal(date.toString(), dayTotals));
        }
        List<DayTotal> historyDays = totals.entrySet().stream().map(e -> new DayTotal(e.getKey().toString(), e.getValue())).toList();
        return new Overview(now, today.toString(), monday.toString(), totals.get(today), weekly, weekDays,
                historyDays, historyStart.toString(), running(active));
    }
    private static String mappedCategory(String category) {
        if (category == null) return null;
        return switch (category) {
            case EFFECTIVE, "JOB", "FRENCH", "COURSE" -> EFFECTIVE;
            case INEFFECTIVE, "ENTERTAINMENT" -> INEFFECTIVE;
            default -> null;
        };
    }
    private static Map<String, Long> emptyTotals() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(EFFECTIVE, 0L); result.put(INEFFECTIVE, 0L); return result;
    }
    private static long overlap(long start, long end, long from, long to) { return Math.max(0, Math.min(end, to) - Math.max(start, from)); }
    private static long start(LocalDate date, ZoneId zone) { return date.atStartOfDay(zone).toInstant().toEpochMilli(); }
    private static void validateRuntime(String runtimeId) {
        if (runtimeId == null || !runtimeId.matches("[A-Za-z0-9_-]{1,80}")) throw bad("桌面运行标识无效");
    }
    private static ZoneId zone(String value) {
        try { return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value); }
        catch (DateTimeException invalid) { throw bad("时区无效"); }
    }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    public record Running(String id, String category, long startedAt, long leaseUntil) { }
    public record PresenceState(long serverNow, Running active) { }
    public record DayTotal(String date, Map<String, Long> totals) { }
    public record Overview(long serverNow, String today, String weekStart, Map<String, Long> daily,
                           Map<String, Long> weekly, List<DayTotal> weekDays, List<DayTotal> historyDays,
                           String historyStart, Running active) { }
}
