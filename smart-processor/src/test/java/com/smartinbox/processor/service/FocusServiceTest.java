package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.controller.FocusController;
import com.smartinbox.processor.entity.FocusSession;
import com.smartinbox.processor.repository.FocusSessionRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"}, showSql = false)
@ContextConfiguration(classes = FocusServiceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FocusServiceTest {
    @Configuration @EntityScan(basePackageClasses = FocusSession.class)
    @EnableJpaRepositories(basePackageClasses = FocusSessionRepository.class)
    @Import({FocusService.class, FocusController.class})
    static class Config { @Bean MutableClock clock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        final AtomicLong value = new AtomicLong();
        void at(String instant) { value.set(Instant.parse(instant).toEpochMilli()); }
        void advance(long millis) { value.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(value.get()); }
        @Override public long millis() { return value.get(); }
    }
    @Autowired FocusService focus;
    @Autowired FocusController controller;
    @Autowired FocusSessionRepository sessions;
    @Autowired MutableClock clock;
    MockMvc mvc;

    @BeforeEach void setup() {
        sessions.deleteAll(); clock.at("2026-10-02T12:00:00Z");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    FocusSession record(String category, long start, Long end) {
        FocusSession result = new FocusSession(); result.setId(UUID.randomUUID().toString());
        result.setCategory(category); result.setStartedAt(start); result.setEndedAt(end);
        return sessions.saveAndFlush(result);
    }
    long total(FocusService.Overview result, String date, String category) {
        return result.historyDays().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow().totals().get(category);
    }

    @Test void readingNeverStartsAndPresenceAutomaticallyStartsIneffective() throws Exception {
        assertNull(focus.overview("UTC").active());
        assertEquals(0, sessions.count());
        var begun = focus.presence("desktop-one", "UTC");
        assertEquals("INEFFECTIVE", begun.active().category());
        assertEquals(clock.millis() + 45_000, begun.active().leaseUntil());
        clock.advance(15_000);
        assertEquals(begun.active().id(), focus.presence("desktop-one", "UTC").active().id());
        assertEquals(15_000, focus.overview("UTC").daily().get("INEFFECTIVE"));
        assertEquals(1, sessions.count());
        mvc.perform(get("/api/focus").param("zone", "UTC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active.category").value("INEFFECTIVE"))
                .andExpect(jsonPath("$.weekDays[0].date").value("2026-09-28"))
                .andExpect(jsonPath("$.historyStart").value("2026-07-02"));
    }

    @Test void switchingAtomicallyDividesTimeAndRejectsDuplicateOrOldRequests() {
        var begun = focus.presence("desktop-one", "UTC");
        clock.advance(10_000);
        var effective = focus.switchCategory(begun.active().id(), "UTC");
        assertEquals("EFFECTIVE", effective.active().category());
        assertEquals(10_000, effective.daily().get("INEFFECTIVE"));
        assertEquals(begun.active().leaseUntil(), effective.active().leaseUntil(), "A phone switch must not extend desktop presence");
        assertThrows(ResponseStatusException.class, () -> focus.switchCategory(begun.active().id(), "UTC"));
        clock.advance(5_000);
        var returned = focus.switchCategory(effective.active().id(), "UTC");
        assertEquals("INEFFECTIVE", returned.active().category());
        assertEquals(5_000, returned.daily().get("EFFECTIVE"));
        assertEquals(1, sessions.findByEndedAtIsNullOrderByStartedAtDesc().size());
    }

    @Test void gracefulStopRecordsUntilStopAndReopenDefaultsToIneffective() {
        var initial = focus.presence("desktop-one", "UTC");
        focus.switchCategory(initial.active().id(), "UTC");
        clock.advance(8_000);
        assertNull(focus.stopPresence("desktop-one", "UTC").active());
        clock.advance(3_600_000);
        assertNull(focus.overview("UTC").active());
        assertEquals(8_000, focus.overview("UTC").daily().get("EFFECTIVE"));
        assertEquals("INEFFECTIVE", focus.presence("desktop-two", "UTC").active().category());
        assertEquals(8_000, focus.overview("UTC").daily().get("EFFECTIVE"));
    }

    @Test void heartbeatGapClosesAtLastConfirmedTimeAndDoesNotCountSleep() {
        var initial = focus.presence("desktop-one", "UTC");
        clock.advance(15_000); focus.presence("desktop-one", "UTC");
        long confirmed = clock.millis();
        clock.advance(4 * 3_600_000);
        assertNull(focus.overview("UTC").active());
        assertEquals(confirmed, sessions.findById(initial.active().id()).orElseThrow().getEndedAt());
        assertEquals(15_000, focus.overview("UTC").daily().get("INEFFECTIVE"));
        var resumed = focus.presence("desktop-one", "UTC");
        assertEquals("INEFFECTIVE", resumed.active().category());
        assertNotEquals(initial.active().id(), resumed.active().id());
        assertEquals(clock.millis(), resumed.active().startedAt());
    }

    @Test void newRuntimeClosesOldAtHeartbeatAndOldStopCannotStopReplacement() {
        var initial = focus.presence("desktop-one", "UTC");
        clock.advance(15_000); focus.presence("desktop-one", "UTC");
        clock.advance(8_000);
        var next = focus.presence("desktop-two", "UTC");
        assertEquals(initial.active().startedAt() + 15_000, sessions.findById(initial.active().id()).orElseThrow().getEndedAt());
        assertEquals(next.active().id(), focus.stopPresence("desktop-one", "UTC").active().id());
        assertEquals(15_000, focus.overview("UTC").daily().get("INEFFECTIVE"));
    }

    @Test void expiredReplacementCannotEndBeforeItsOwnStart() {
        var initial = focus.presence("desktop-one", "UTC");
        clock.advance(20_000);
        var replacement = focus.switchCategory(initial.active().id(), "UTC");
        clock.advance(60_000);
        assertNull(focus.overview("UTC").active());
        FocusSession stored = sessions.findById(replacement.active().id()).orElseThrow();
        assertEquals(stored.getStartedAt(), stored.getEndedAt());
        assertEquals(20_000, focus.overview("UTC").daily().get("INEFFECTIVE"));
        assertEquals(0, focus.overview("UTC").daily().get("EFFECTIVE"));
    }

    @Test void legacyRunningRecordsAreClosedWithoutInventingOfflineTime() {
        FocusSession old = record("COURSE", clock.millis() - 20 * 86_400_000L, null);
        assertNull(focus.overview("UTC").active());
        assertEquals(old.getStartedAt(), sessions.findById(old.getId()).orElseThrow().getEndedAt());
        assertEquals(0, focus.overview("UTC").daily().get("EFFECTIVE"));
    }

    @Test void oldCategoriesMapToTwoTotalsAndAtLeastThreeCalendarMonthsAreRetained() {
        long today = clock.millis() - 3_600_000;
        for (String category : List.of("JOB", "FRENCH", "COURSE")) record(category, today, today + 60_000);
        record("ENTERTAINMENT", today, today + 120_000);
        long first = Instant.parse("2026-07-02T12:00:00Z").toEpochMilli();
        record("JOB", first, first + 120_000);
        FocusSession older = record("COURSE", first - 180 * 86_400_000L, first - 180 * 86_400_000L + 60_000);
        var result = focus.overview("UTC");
        assertEquals(93, result.historyDays().size());
        assertEquals("2026-07-02", result.historyStart());
        assertEquals(120_000, total(result, "2026-07-02", "EFFECTIVE"));
        assertEquals(180_000, result.daily().get("EFFECTIVE"));
        assertEquals(120_000, result.daily().get("INEFFECTIVE"));
        assertTrue(sessions.existsById(older.getId()), "Viewing history must not delete older records");
    }

    @Test void localMidnightAllocatesOneSessionAcrossTwoDays() {
        clock.at("2026-10-02T06:00:00Z");
        long midnight = Instant.parse("2026-10-02T04:00:00Z").toEpochMilli();
        record("EFFECTIVE", midnight - 30 * 60_000, midnight + 30 * 60_000);
        var result = focus.overview("America/New_York");
        assertEquals(30 * 60_000, result.daily().get("EFFECTIVE"));
        assertEquals(30 * 60_000, total(result, "2026-10-01", "EFFECTIVE"));
    }

    @Test void daylightSavingDaysUseLocalBoundariesRatherThanFixed24Hours() {
        ZoneId zone = ZoneId.of("America/New_York");
        for (String day : List.of("2026-03-08", "2026-11-01")) {
            sessions.deleteAll();
            LocalDate date = LocalDate.parse(day);
            long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            clock.value.set(to + 3_600_000);
            record("EFFECTIVE", from, to);
            assertEquals(day.endsWith("03-08") ? 23 * 3_600_000 : 25 * 3_600_000,
                    total(focus.overview(zone.getId()), day, "EFFECTIVE"));
        }
    }

    @Test void simultaneousRequestsStillCommitExactlyOneActiveSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> heartbeats = new ArrayList<>();
            for (int i = 0; i < 8; i++) heartbeats.add(() -> focus.presence("desktop-one", "UTC").active().id());
            Set<String> ids = new HashSet<>();
            for (Future<String> future : executor.invokeAll(heartbeats)) ids.add(future.get());
            assertEquals(1, ids.size());
            String originalId = ids.iterator().next();
            List<Callable<Boolean>> switches = new ArrayList<>();
            for (int i = 0; i < 8; i++) switches.add(() -> {
                try { focus.switchCategory(originalId, "UTC"); return true; }
                catch (ResponseStatusException conflict) { assertEquals(409, conflict.getStatusCode().value()); return false; }
            });
            int accepted = 0;
            for (Future<Boolean> future : executor.invokeAll(switches)) if (future.get()) accepted++;
            assertEquals(1, accepted);
            assertEquals(2, sessions.count());
            assertEquals(1, sessions.findByEndedAtIsNullOrderByStartedAtDesc().size());
        } finally { executor.shutdownNow(); }
    }

    @Test void runtimeFieldsNeverChangeBackupJsonSchema() throws Exception {
        var begun = focus.presence("private-desktop-runtime", "UTC");
        String json = new ObjectMapper().writeValueAsString(sessions.findById(begun.active().id()).orElseThrow());
        assertFalse(json.contains("runtime"));
        assertFalse(json.contains("Heartbeat"));
        assertFalse(json.contains("private-desktop"));
    }

    @Test void invalidInputsAndRetiredEndpointsCannotStartTimers() throws Exception {
        mvc.perform(post("/api/focus/presence").contentType("application/json").content("{\"runtimeId\":\"\",\"zone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/focus/presence").contentType("application/json").content("{\"runtimeId\":\"desktop\",\"zone\":\"invalid-zone\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/focus/switch").contentType("application/json").content("{\"id\":\"missing\",\"zone\":\"UTC\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/focus/start").contentType("application/json").content("{\"category\":\"COURSE\"}"))
                .andExpect(status().isGone());
        mvc.perform(put("/api/focus/limit").contentType("application/json").content("{\"minutes\":60}"))
                .andExpect(status().isGone());
        assertEquals(0, sessions.count());
    }
}
