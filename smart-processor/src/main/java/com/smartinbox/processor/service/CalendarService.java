package com.smartinbox.processor.service;

import com.smartinbox.processor.entity.CalendarEvent;
import com.smartinbox.processor.repository.CalendarEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CalendarService {
    private static final int MAX_DEFINITIONS = 2000;
    private static final int MAX_OCCURRENCES = 20000;
    private static final Duration MAX_DURATION = Duration.ofDays(7);
    private static final Set<String> KINDS = Set.of("COURSE", "EXAM", "INTERVIEW", "PERSONAL");
    private final CalendarEventRepository events;

    public CalendarService(CalendarEventRepository events) { this.events = events; }

    @Transactional(readOnly = true)
    public CalendarView list(String from, String to, String zone) {
        LocalDate first = date(from), last = date(to);
        ZoneId viewZone = zone(zone);
        long days = ChronoUnit.DAYS.between(first, last);
        if (days < 1 || days > 62) throw bad("日历日期范围须为 1 至 62 天，结束日期不包含在内");
        long rangeStart = first.atStartOfDay(viewZone).toInstant().toEpochMilli();
        long rangeEnd = last.atStartOfDay(viewZone).toInstant().toEpochMilli();
        var candidates = events.findOverlapping(rangeStart, rangeEnd, PageRequest.of(0, MAX_DEFINITIONS + 1));
        if (candidates.size() > MAX_DEFINITIONS) throw bad("范围内日程过多，请缩小日期范围");
        List<Definition> definitions = new ArrayList<>();
        List<Occurrence> occurrences = new ArrayList<>();
        for (CalendarEvent event : candidates) {
            int before = occurrences.size();
            expand(event, rangeStart, rangeEnd, occurrences);
            if (occurrences.size() > MAX_OCCURRENCES) throw bad("范围内日程过多，请缩小日期范围");
            if (occurrences.size() > before) definitions.add(definition(event));
        }
        occurrences.sort(Comparator.comparingLong(Occurrence::startAt).thenComparing(Occurrence::key));
        return new CalendarView(List.copyOf(definitions), List.copyOf(occurrences), viewZone.getId());
    }

    @Transactional
    public Definition create(Input input) {
        CalendarEvent event = validated(input);
        event.setId(UUID.randomUUID().toString());
        return definition(events.saveAndFlush(event));
    }

    @Transactional
    public Definition update(String id, Input input) {
        if (input == null) throw bad("日程不能为空");
        requireVersion(input.version());
        CalendarEvent next = validated(input);
        CalendarEvent event = find(id);
        checkVersion(event, input.version());
        copy(next, event);
        return definition(events.saveAndFlush(event));
    }

    @Transactional
    public void delete(String id, Long version) {
        requireVersion(version);
        CalendarEvent event = find(id);
        checkVersion(event, version);
        events.delete(event);
        events.flush();
    }

    @Transactional(readOnly = true)
    public List<Definition> exportDefinitions() {
        return events.findAll().stream().map(CalendarService::definition).sorted(Comparator.comparing(Definition::id)).toList();
    }

    /** Validate the complete backup before any import writes. Existing ids are preserved. */
    public void validateBackup(List<Definition> definitions) { backupEntities(definitions); }

    @Transactional
    public void restoreBackup(List<Definition> definitions) {
        List<CalendarEvent> validated = backupEntities(definitions);
        for (CalendarEvent event : validated) if (!events.existsById(event.getId())) events.save(event);
        events.flush();
    }

    private List<CalendarEvent> backupEntities(List<Definition> definitions) {
        if (definitions == null || definitions.size() > 10000) throw bad("日历备份最多包含 10000 条日程");
        Set<String> ids = new HashSet<>();
        List<CalendarEvent> validated = new ArrayList<>();
        for (Definition definition : definitions) {
            if (definition == null) throw bad("日历备份包含空日程");
            String id = validId(definition.id());
            if (!ids.add(id)) throw bad("日历备份包含重复编号");
            CalendarEvent event = validated(new Input(definition.title(), definition.kind(), definition.startLocal(),
                    definition.endLocal(), definition.zone(), definition.recurrence(), definition.daysOfWeek(),
                    definition.repeatUntil(), definition.location(), definition.notes(), null));
            event.setId(id);
            validated.add(event);
        }
        return validated;
    }

    private CalendarEvent validated(Input input) {
        if (input == null) throw bad("日程不能为空");
        CalendarEvent event = new CalendarEvent();
        event.setTitle(text(input.title(), 300, true));
        if (input.kind() == null || !KINDS.contains(input.kind())) throw bad("日程类型无效");
        event.setKind(input.kind());
        ZoneId eventZone = zone(input.zone());
        LocalDateTime start = localDateTime(input.startLocal()), end = localDateTime(input.endLocal());
        Duration duration = Duration.between(start, end);
        if (duration.isNegative() || duration.isZero() || duration.compareTo(MAX_DURATION) > 0)
            throw bad("结束时间须晚于开始时间，单次日程最多为 7 天");
        Instant startAt = instant(start, eventZone), endAt = instant(end, eventZone);
        if (startAt == null || endAt == null) throw bad("该本地时间因夏令时切换而不存在，请调整时间");
        if (!endAt.isAfter(startAt) || Duration.between(startAt, endAt).compareTo(MAX_DURATION) > 0)
            throw bad("结束时间须晚于开始时间，单次日程最多为 7 天");
        event.setStartLocal(start.toString()); event.setEndLocal(end.toString()); event.setZone(eventZone.getId());
        String recurrence = input.recurrence() == null ? "NONE" : input.recurrence();
        if (!Set.of("NONE", "WEEKLY").contains(recurrence)) throw bad("重复规则无效");
        event.setRecurrence(recurrence);
        List<Integer> weekdays = input.daysOfWeek() == null ? List.of() : input.daysOfWeek();
        if ("WEEKLY".equals(recurrence)) {
            if (weekdays.isEmpty() || weekdays.size() > 7) throw bad("每周重复须选择星期");
            int mask = 0;
            for (Integer day : weekdays) {
                if (day == null || day < 1 || day > 7 || (mask & (1 << (day - 1))) != 0) throw bad("星期须为不重复的 1 至 7");
                mask |= 1 << (day - 1);
            }
            LocalDate until = date(input.repeatUntil());
            if (until.isBefore(start.toLocalDate()) || until.isAfter(start.toLocalDate().plusYears(2)))
                throw bad("重复结束日期须在开始日期至两年内");
            if (!hasWeeklyOccurrence(start, duration, eventZone, mask, until))
                throw bad("重复日期范围内没有有效日程，请调整星期、时间或结束日期");
            event.setWeekdayMask(mask); event.setRepeatUntil(until);
            // This is a conservative SQL envelope; actual gap dates are skipped in expand().
            endAt = until.atTime(start.toLocalTime()).plus(duration).atZone(eventZone).toInstant();
        } else if (!weekdays.isEmpty() || input.repeatUntil() != null) {
            throw bad("不重复日程不能设置星期或重复结束日期");
        }
        event.setBoundsStart(startAt.toEpochMilli()); event.setBoundsEnd(endAt.toEpochMilli());
        event.setLocation(text(input.location(), 300, false)); event.setNotes(text(input.notes(), 4000, false));
        event.setRevisionNonce(UUID.randomUUID().toString());
        return event;
    }

    private static boolean hasWeeklyOccurrence(LocalDateTime start, Duration duration, ZoneId zone, int mask, LocalDate until) {
        // Validation already limits this search to two years; most series return within one week.
        for (LocalDate day = start.toLocalDate(); !day.isAfter(until); day = day.plusDays(1)) {
            if ((mask & (1 << (day.getDayOfWeek().getValue() - 1))) == 0) continue;
            LocalDateTime localStart = day.atTime(start.toLocalTime());
            Instant starts = instant(localStart, zone), ends = instant(localStart.plus(duration), zone);
            if (starts != null && ends != null && ends.isAfter(starts)) return true;
        }
        return false;
    }

    private static void expand(CalendarEvent event, long rangeStart, long rangeEnd, List<Occurrence> result) {
        ZoneId zone = ZoneId.of(event.getZone());
        LocalDateTime originalStart = LocalDateTime.parse(event.getStartLocal());
        LocalDateTime originalEnd = LocalDateTime.parse(event.getEndLocal());
        if ("NONE".equals(event.getRecurrence())) {
            occurrence(event, originalStart, originalEnd, zone, rangeStart, rangeEnd, result);
            return;
        }
        Duration duration = Duration.between(originalStart, originalEnd);
        // Look back across the maximum event duration to include events spanning into the range.
        LocalDate first = Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate().minusDays(7);
        if (first.isBefore(originalStart.toLocalDate())) first = originalStart.toLocalDate();
        LocalDate last = Instant.ofEpochMilli(rangeEnd).atZone(zone).toLocalDate();
        if (last.isAfter(event.getRepeatUntil())) last = event.getRepeatUntil();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if ((event.getWeekdayMask() & (1 << (day.getDayOfWeek().getValue() - 1))) == 0) continue;
            LocalDateTime start = day.atTime(originalStart.toLocalTime());
            occurrence(event, start, start.plus(duration), zone, rangeStart, rangeEnd, result);
        }
    }

    private static void occurrence(CalendarEvent event, LocalDateTime start, LocalDateTime end, ZoneId zone,
                                   long rangeStart, long rangeEnd, List<Occurrence> result) {
        Instant starts = instant(start, zone), ends = instant(end, zone);
        if (starts == null || ends == null || !ends.isAfter(starts)) return;
        long startAt = starts.toEpochMilli(), endAt = ends.toEpochMilli();
        if (startAt >= rangeEnd || endAt <= rangeStart) return;
        result.add(new Occurrence(event.getId() + ":" + start, event.getId(), event.getTitle(), event.getKind(),
                startAt, endAt, event.getLocation(), event.getNotes(), event.getZone(), "WEEKLY".equals(event.getRecurrence()), event.getVersion()));
    }

    /** Missing spring-forward times are never shifted. A repeated autumn time uses the earlier offset. */
    private static Instant instant(LocalDateTime local, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(local);
        return offsets.isEmpty() ? null : local.toInstant(offsets.get(0));
    }

    private CalendarEvent find(String id) {
        validId(id);
        return events.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "日程不存在或已被删除"));
    }
    private static String validId(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) { throw bad("日程编号无效"); }
    }
    private static void requireVersion(Long version) { if (version == null || version < 0) throw bad("请提供日程版本，刷新后重试"); }
    private static void checkVersion(CalendarEvent event, Long version) {
        if (!Objects.equals(event.getVersion(), version)) throw new ResponseStatusException(HttpStatus.CONFLICT, "日程已被其他页面更新，请刷新后重试");
    }
    private static ZoneId zone(String value) {
        if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) throw bad("请提供有效的 IANA 时区");
        return ZoneId.of(value);
    }
    private static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeException("date");
            return LocalDate.parse(value);
        } catch (DateTimeException error) { throw bad("日期无效，请使用 YYYY-MM-DD"); }
    }
    private static LocalDateTime localDateTime(String value) {
        try {
            if (value == null || value.length() > 40 || !value.matches("\\d{4}-\\d{2}-\\d{2}T.*")) throw new DateTimeException("date-time");
            return LocalDateTime.parse(value);
        } catch (DateTimeException error) { throw bad("时间无效，请使用本地日期和时间"); }
    }
    private static String text(String value, int maximum, boolean required) {
        String result = value == null ? "" : value.trim();
        if ((required && result.isEmpty()) || result.length() > maximum) throw bad("日程文本为空或过长");
        return result;
    }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static void copy(CalendarEvent from, CalendarEvent to) {
        to.setTitle(from.getTitle()); to.setKind(from.getKind()); to.setStartLocal(from.getStartLocal()); to.setEndLocal(from.getEndLocal());
        to.setZone(from.getZone()); to.setRecurrence(from.getRecurrence()); to.setWeekdayMask(from.getWeekdayMask());
        to.setRepeatUntil(from.getRepeatUntil()); to.setLocation(from.getLocation()); to.setNotes(from.getNotes());
        to.setBoundsStart(from.getBoundsStart()); to.setBoundsEnd(from.getBoundsEnd()); to.setRevisionNonce(from.getRevisionNonce());
    }
    private static Definition definition(CalendarEvent event) {
        List<Integer> weekdays = new ArrayList<>();
        for (int day = 1; day <= 7; day++) if ((event.getWeekdayMask() & (1 << (day - 1))) != 0) weekdays.add(day);
        return new Definition(event.getId(), event.getTitle(), event.getKind(), event.getStartLocal(), event.getEndLocal(),
                event.getZone(), event.getRecurrence(), List.copyOf(weekdays), event.getRepeatUntil() == null ? null : event.getRepeatUntil().toString(),
                event.getLocation(), event.getNotes(), event.getVersion());
    }

    public record Input(String title, String kind, String startLocal, String endLocal, String zone, String recurrence,
                        List<Integer> daysOfWeek, String repeatUntil, String location, String notes, Long version) {}
    public record Definition(String id, String title, String kind, String startLocal, String endLocal, String zone,
                             String recurrence, List<Integer> daysOfWeek, String repeatUntil, String location, String notes, Long version) {}
    public record Occurrence(String key, String eventId, String title, String kind, long startAt, long endAt,
                             String location, String notes, String zone, boolean recurring, Long version) {}
    public record CalendarView(List<Definition> events, List<Occurrence> occurrences, String zone) {}
}
