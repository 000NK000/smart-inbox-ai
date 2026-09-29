package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartinbox.processor.controller.CalendarController;
import com.smartinbox.processor.entity.CalendarEvent;
import com.smartinbox.processor.repository.CalendarEventRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"}, showSql = false)
@ContextConfiguration(classes = CalendarServiceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CalendarServiceTest {
    @Configuration @EntityScan(basePackageClasses = CalendarEvent.class)
    @EnableJpaRepositories(basePackageClasses = CalendarEventRepository.class)
    @Import({CalendarService.class, CalendarController.class})
    static class Config {}
    @Autowired CalendarService calendar;
    @Autowired CalendarEventRepository repository;
    @Autowired CalendarController controller;
    MockMvc mvc;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setup() {
        repository.deleteAll();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    CalendarService.Input once(String title, String start, String end, String zone, Long version) {
        return new CalendarService.Input(title, "COURSE", start, end, zone, "NONE", List.of(), null, "Room 3", "Bring notes", version);
    }
    CalendarService.Input weekly(String start, String end, String zone, List<Integer> days, String until) {
        return new CalendarService.Input("Weekly class", "COURSE", start, end, zone, "WEEKLY", days, until, "Room 3", "Bring notes", null);
    }
    String json(CalendarService.Input input) throws Exception { return mapper.writeValueAsString(input); }
    List<CalendarService.Occurrence> range(String from, String to, String zone) { return calendar.list(from, to, zone).occurrences(); }

    @Test void committedCrudReturnsDefinitionsAndOccurrencesThenDeletesWholeSeries() throws Exception {
        var input = weekly("2026-09-21T09:00", "2026-09-21T10:15", "Asia/Shanghai", List.of(1, 3), "2026-10-21");
        JsonNode created = mapper.readTree(mvc.perform(post("/api/calendar/events").contentType("application/json").content(json(input)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0)).andReturn().getResponse().getContentAsByteArray());
        String id = created.path("id").asText(); assertEquals(id, UUID.fromString(id).toString());
        assertEquals("Weekly class", repository.findById(id).orElseThrow().getTitle());
        var edit = new CalendarService.Input("Changed class", "EXAM", input.startLocal(), input.endLocal(), input.zone(), input.recurrence(),
                input.daysOfWeek(), input.repeatUntil(), "Exam hall", "Bring ID", 0L);
        mvc.perform(put("/api/calendar/events/" + id).contentType("application/json").content(json(edit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/calendar").param("from", "2026-09-21").param("to", "2026-09-28").param("zone", "Asia/Shanghai"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.occurrences.length()").value(2)).andExpect(jsonPath("$.occurrences[0].title").value("Changed class"))
                .andExpect(jsonPath("$.occurrences[1].version").value(1)).andExpect(jsonPath("$.zone").value("Asia/Shanghai"));
        mvc.perform(delete("/api/calendar/events/" + id).param("version", "1")).andExpect(status().isNoContent());
        assertEquals(0, repository.count()); assertTrue(range("2026-10-01", "2026-11-01", "UTC").isEmpty());
    }

    @Test void staleOrMissingVersionsCannotOverwriteOrDeleteAndIdenticalEditsAdvanceVersion() throws Exception {
        var input = once("Saved", "2026-09-20T09:00", "2026-09-20T10:00", "UTC", null);
        var created = calendar.create(input);
        var edit = once(input.title(), input.startLocal(), input.endLocal(), input.zone(), created.version());
        mvc.perform(put("/api/calendar/events/" + created.id()).contentType("application/json").content(json(edit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/calendar/events/" + created.id()).contentType("application/json").content(json(edit))).andExpect(status().isConflict());
        mvc.perform(delete("/api/calendar/events/" + created.id()).param("version", "0")).andExpect(status().isConflict());
        mvc.perform(put("/api/calendar/events/" + created.id()).contentType("application/json").content(json(input))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/calendar/events/" + created.id())).andExpect(status().isBadRequest());
        assertEquals(1L, repository.findById(created.id()).orElseThrow().getVersion());
    }

    @Test void simultaneousHttpEditsHaveOneWinnerAndReturn409ForEveryLoser() throws Exception {
        var created = calendar.create(once("Initial", "2026-09-20T09:00", "2026-09-20T10:00", "UTC", null));
        var executor = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6); var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                    return mvc.perform(put("/api/calendar/events/" + created.id()).contentType("application/json")
                            .content(json(once("Editor " + n, created.startLocal(), created.endLocal(), created.zone(), created.version()))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get(15, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
            assertEquals(5, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
            assertEquals(1L, repository.findById(created.id()).orElseThrow().getVersion());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void halfOpenRangeIncludesCrossMidnightAndUsesViewerZone() {
        var crosses = calendar.create(once("Cross midnight", "2026-09-20T23:30", "2026-09-21T01:00", "UTC", null));
        calendar.create(once("Ends at boundary", "2026-09-20T20:00", "2026-09-21T00:00", "UTC", null));
        calendar.create(once("Starts at boundary", "2026-09-22T00:00", "2026-09-22T01:00", "UTC", null));
        assertEquals(List.of(crosses.id()), range("2026-09-21", "2026-09-22", "UTC").stream().map(CalendarService.Occurrence::eventId).toList());
        assertEquals(2, range("2026-09-21", "2026-09-22", "Asia/Tokyo").size());
    }

    @Test void weeklyOccurrencesHonorWeekdaysInclusiveUntilAndCrossMidnight() {
        var created = calendar.create(weekly("2026-09-21T23:30", "2026-09-22T01:00", "UTC", List.of(1, 3), "2026-09-30"));
        var all = range("2026-09-01", "2026-10-10", "UTC");
        assertEquals(List.of("2026-09-21", "2026-09-23", "2026-09-28", "2026-09-30"), all.stream()
                .map(row -> Instant.ofEpochMilli(row.startAt()).atZone(ZoneId.of("UTC")).toLocalDate().toString()).toList());
        assertEquals(4, all.stream().map(CalendarService.Occurrence::key).distinct().count());
        assertEquals(created.id(), range("2026-10-01", "2026-10-02", "UTC").get(0).eventId());
        assertEquals(created.id(), range("2026-09-22", "2026-09-23", "UTC").get(0).eventId());
        assertTrue(range("2026-10-02", "2026-10-10", "UTC").isEmpty());
    }

    @Test void weeklyWallTimeIsPreservedAcrossSpringDstAndGapOccurrenceIsSkipped() {
        calendar.create(weekly("2026-03-01T09:00", "2026-03-01T10:00", "America/New_York", List.of(7), "2026-03-15"));
        var rows = range("2026-03-01", "2026-03-16", "America/New_York");
        assertEquals(3, rows.size());
        assertEquals(Instant.parse("2026-03-01T14:00:00Z").toEpochMilli(), rows.get(0).startAt());
        assertEquals(Instant.parse("2026-03-08T13:00:00Z").toEpochMilli(), rows.get(1).startAt());
        for (var row : rows) assertEquals(LocalTime.of(9, 0), Instant.ofEpochMilli(row.startAt()).atZone(ZoneId.of(row.zone())).toLocalTime());
        repository.deleteAll();
        calendar.create(weekly("2026-03-01T02:30", "2026-03-01T03:30", "America/New_York", List.of(7), "2026-03-15"));
        var gaps = range("2026-03-01", "2026-03-16", "America/New_York");
        assertEquals(2, gaps.size());
        assertEquals(List.of(1, 15), gaps.stream().map(row -> Instant.ofEpochMilli(row.startAt()).atZone(ZoneId.of(row.zone())).getDayOfMonth()).toList());
    }

    @Test void autumnOverlapUsesEarlierOffsetAndLongEventKeepsEndWallTime() {
        calendar.create(weekly("2026-10-25T01:30", "2026-10-25T02:30", "America/New_York", List.of(7), "2026-11-08"));
        var rows = range("2026-10-25", "2026-11-09", "America/New_York");
        assertEquals(3, rows.size());
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), rows.get(1).startAt());
        assertEquals(Instant.parse("2026-11-01T07:30:00Z").toEpochMilli(), rows.get(1).endAt());
        assertEquals(Instant.parse("2026-11-08T06:30:00Z").toEpochMilli(), rows.get(2).startAt());
    }

    @Test void invalidDatesTimezoneDurationRecurrenceAndTextCannotPersist() throws Exception {
        ObjectNode good = mapper.valueToTree(once("Valid", "2026-09-20T09:00", "2026-09-20T10:00", "UTC", null));
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(good.deepCopy().put("startLocal", "2026-02-30T09:00"));
        invalid.add(good.deepCopy().put("startLocal", "2026-09-20T25:00"));
        invalid.add(good.deepCopy().put("startLocal", "2026-09-20T09:00Z"));
        invalid.add(good.deepCopy().put("endLocal", "2026-09-20T08:00"));
        invalid.add(good.deepCopy().put("endLocal", "2026-09-20T09:00"));
        invalid.add(good.deepCopy().put("endLocal", "2026-09-27T09:01"));
        invalid.add(good.deepCopy().put("zone", "Invented/Nowhere"));
        invalid.add(good.deepCopy().put("zone", "+08:00"));
        invalid.add(good.deepCopy().put("title", " "));
        invalid.add(good.deepCopy().put("notes", "x".repeat(4001)));
        invalid.add(good.deepCopy().put("kind", "TASK"));
        invalid.add(good.deepCopy().put("recurrence", "DAILY"));
        invalid.add(good.deepCopy().put("repeatUntil", "2026-10-01"));
        invalid.add(good.deepCopy().put("recurrence", "WEEKLY").put("repeatUntil", "2026-10-01"));
        ObjectNode weekly = mapper.valueToTree(weekly("2026-09-21T09:00", "2026-09-21T10:00", "UTC", List.of(1), "2026-10-01"));
        invalid.add(weekly.deepCopy().put("repeatUntil", "2028-09-22"));
        invalid.add(weekly.deepCopy().put("repeatUntil", "2026-09-20"));
        invalid.add(weekly.deepCopy().put("repeatUntil", "2026-02-30"));
        ObjectNode repeated = weekly.deepCopy(); repeated.putArray("daysOfWeek").add(1).add(1); invalid.add(repeated);
        ObjectNode zero = weekly.deepCopy(); zero.putArray("daysOfWeek").add(0); invalid.add(zero);
        ObjectNode eight = weekly.deepCopy(); eight.putArray("daysOfWeek").add(8); invalid.add(eight);
        invalid.add(mapper.valueToTree(once("Gap", "2026-03-08T02:30", "2026-03-08T03:30", "America/New_York", null)));
        invalid.add(mapper.valueToTree(once("Gap end", "2026-03-08T01:30", "2026-03-08T02:30", "America/New_York", null)));
        for (ObjectNode payload : invalid) mvc.perform(post("/api/calendar/events").contentType("application/json").content(payload.toString()))
                .andExpect(status().isBadRequest());
        assertEquals(0, repository.count());
    }

    @Test void rangesRequireStrictDatesValidZoneAndAtMost62Days() throws Exception {
        for (String[] bounds : List.of(new String[]{"2026-02-30", "2026-03-02"}, new String[]{"2026-09-21", "2026-09-20"},
                new String[]{"2026-09-20", "2026-09-20"}, new String[]{"2026-01-01", "2026-03-05"}))
            mvc.perform(get("/api/calendar").param("from", bounds[0]).param("to", bounds[1]).param("zone", "UTC")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/calendar").param("from", "2026-01-01").param("to", "2026-03-04").param("zone", "UTC")).andExpect(status().isOk());
        mvc.perform(get("/api/calendar").param("from", "2026-09-20").param("to", "2026-09-21").param("zone", "Invalid")).andExpect(status().isBadRequest());
    }

    @Test void multiYearSeriesOnlyExpandsRequestedRange() {
        calendar.create(weekly("2026-01-01T09:00", "2026-01-01T10:00", "UTC", List.of(1, 2, 3, 4, 5, 6, 7), "2028-01-01"));
        assertEquals(7, range("2027-06-01", "2027-06-08", "UTC").size());
    }

    @Test void emptyWeeklySeriesAndSeriesWithOnlyDstGapsAreRejected() throws Exception {
        var wrongWeekday = weekly("2026-09-21T09:00", "2026-09-21T10:00", "UTC", List.of(3), "2026-09-21");
        var startGap = weekly("2026-03-02T02:30", "2026-03-02T03:30", "America/New_York", List.of(7), "2026-03-08");
        var endGap = weekly("2026-03-02T01:30", "2026-03-02T02:30", "America/New_York", List.of(7), "2026-03-08");
        for (var input : List.of(wrongWeekday, startGap, endGap))
            mvc.perform(post("/api/calendar/events").contentType("application/json").content(json(input))).andExpect(status().isBadRequest());
        assertEquals(0, repository.count());
        var accepted = calendar.create(weekly("2026-03-02T02:30", "2026-03-02T03:30", "America/New_York", List.of(7), "2026-03-15"));
        assertEquals(1, range("2026-03-02", "2026-03-16", "America/New_York").size());
        var invalidEdit = new CalendarService.Input(startGap.title(), startGap.kind(), startGap.startLocal(), startGap.endLocal(),
                startGap.zone(), startGap.recurrence(), startGap.daysOfWeek(), startGap.repeatUntil(), startGap.location(), startGap.notes(), accepted.version());
        mvc.perform(put("/api/calendar/events/" + accepted.id()).contentType("application/json").content(json(invalidEdit))).andExpect(status().isBadRequest());
        assertEquals("2026-03-15", repository.findById(accepted.id()).orElseThrow().getRepeatUntil().toString());
        assertEquals(accepted.version(), repository.findById(accepted.id()).orElseThrow().getVersion());
    }

    @Test void backupMergeValidatesBeforeWritesPreservesExistingIdsAndIgnoresImportedVersions() {
        var original = calendar.create(once("Original", "2026-09-20T09:00", "2026-09-20T10:00", "UTC", null));
        var other = new CalendarService.Definition(UUID.randomUUID().toString(), "Imported", original.kind(), original.startLocal(), original.endLocal(),
                original.zone(), original.recurrence(), original.daysOfWeek(), original.repeatUntil(), original.location(), original.notes(), 500L);
        calendar.restoreBackup(List.of(original, other));
        assertEquals(2, calendar.exportDefinitions().size());
        assertEquals(0L, repository.findById(other.id()).orElseThrow().getVersion());
        calendar.update(original.id(), once("Edited", original.startLocal(), original.endLocal(), original.zone(), 0L));
        calendar.restoreBackup(List.of(original, other));
        assertEquals("Edited", repository.findById(original.id()).orElseThrow().getTitle());
        var bad = new CalendarService.Definition("bad-id", other.title(), other.kind(), other.startLocal(), other.endLocal(), other.zone(),
                other.recurrence(), other.daysOfWeek(), other.repeatUntil(), other.location(), other.notes(), null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> calendar.restoreBackup(List.of(other, bad)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> calendar.validateBackup(List.of(other, other)));
        assertEquals(2, repository.count());
    }
}
