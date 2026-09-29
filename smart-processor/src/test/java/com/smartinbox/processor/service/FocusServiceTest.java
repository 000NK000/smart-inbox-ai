package com.smartinbox.processor.service;

import com.smartinbox.processor.controller.FocusController;
import com.smartinbox.processor.entity.FocusSession;
import com.smartinbox.processor.repository.*;
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
import java.util.UUID;

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
    static class Config { }

    @Autowired FocusService focus;
    @Autowired FocusController controller;
    @Autowired FocusSessionRepository sessions;
    @Autowired AppPreferenceRepository preferences;
    MockMvc mvc;

    @BeforeEach void setup() {
        sessions.deleteAll(); preferences.deleteAll();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void timerPersistsAcrossReadsAndOnlyOneCategoryCanRun() throws Exception {
        assertEquals(300, focus.overview("America/Toronto").entertainmentLimitMinutes());
        var begun = focus.start("FRENCH", null, "America/Toronto");
        assertEquals("FRENCH", begun.active().category());
        assertThrows(ResponseStatusException.class, () -> focus.start("COURSE", null, "America/Toronto"));
        Thread.sleep(25);
        assertEquals(begun.active().id(), focus.overview("America/Toronto").active().id());
        var ended = focus.stop(begun.active().id(), "America/Toronto");
        assertNull(ended.active());
        assertTrue(ended.daily().get("FRENCH") >= 20);
        assertNotNull(sessions.findById(begun.active().id()).orElseThrow().getEndedAt());
        mvc.perform(get("/api/focus").param("zone", "America/Toronto"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entertainmentLimitMinutes").value(300));
    }

    @Test void entertainmentStopsAtQuotaEvenWhenPageWasClosed() {
        focus.setLimit(1, "UTC");
        var started = focus.start("ENTERTAINMENT", null, "UTC");
        FocusSession saved = sessions.findById(started.active().id()).orElseThrow();
        saved.setStartedAt(System.currentTimeMillis() - 65_000);
        sessions.saveAndFlush(saved);
        var ended = focus.overview("UTC");
        assertNull(ended.active());
        assertEquals(60_000L, ended.daily().get("ENTERTAINMENT"));
        assertThrows(ResponseStatusException.class, () -> focus.start("ENTERTAINMENT", null, "UTC"));
    }

    @Test void todayOnlyGetsOverlappingPartOfSessionAcrossMidnight() {
        ZoneId zone = ZoneId.of("America/Toronto");
        LocalDate today = LocalDate.now(zone);
        long midnight = today.atStartOfDay(zone).toInstant().toEpochMilli();
        FocusSession session = new FocusSession();
        session.setId(UUID.randomUUID().toString()); session.setCategory("COURSE");
        session.setStartedAt(midnight - 30 * 60_000L);
        session.setEndedAt(midnight + 30 * 60_000L);
        sessions.saveAndFlush(session);
        assertEquals(30 * 60_000L, focus.overview(zone.getId()).daily().get("COURSE"));
    }

    @Test void invalidInputIsRejectedWithoutCreatingSessions() throws Exception {
        mvc.perform(post("/api/focus/start").contentType("application/json")
                .content("{\"category\":\"UNKNOWN\",\"zone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/focus/limit").contentType("application/json")
                .content("{\"minutes\":0,\"zone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, sessions.count());
    }
}
