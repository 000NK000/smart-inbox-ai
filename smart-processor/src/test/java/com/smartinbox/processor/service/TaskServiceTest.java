package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.*;
import com.smartinbox.processor.controller.TaskController;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"})
@ContextConfiguration(classes = TaskServiceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskServiceTest {
    @Configuration @EntityScan(basePackageClasses = TaskItem.class)
    @EnableJpaRepositories(basePackageClasses = TaskItemRepository.class)
    @Import({TaskController.class, TaskService.class})
    static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper(); } }
    @Autowired TaskController controller;
    @Autowired TaskService service;
    @Autowired TaskItemRepository tasks;
    @Autowired MailSummaryRepository mails;
    @Autowired MailTaskAnalysisRepository analyses;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    @BeforeEach void setup() { tasks.deleteAll(); analyses.deleteAll(); mails.deleteAll(); mvc = MockMvcBuilders.standaloneSetup(controller).build(); }
    JsonNode create(String text) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/tasks").contentType("application/json").content(mapper.createObjectNode().put("text", text).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    @Test void postponingDeadlineTracksRepeatedDelaysForWeeklyReview() throws Exception {
        long first = System.currentTimeMillis() + 86400000L;
        var created = mapper.readTree(mvc.perform(post("/api/tasks").contentType("application/json")
                .content("{\"text\":\"Prepare interview\",\"dueAt\":" + first + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String id = created.path("id").asText();
        for (int i = 1; i <= 2; i++) {
            long next = first + i * 86400000L;
            mvc.perform(put("/api/tasks/" + id).contentType("application/json")
                    .content("{\"text\":\"Prepare interview\",\"dueAt\":" + next + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.rescheduleCount").value(i));
        }
        assertEquals(2, tasks.findById(id).orElseThrow().getRescheduleCount());
    }
    @Test void completionKeepsHistoryAndCanBeReopened() throws Exception {
        var created = create("Complete report"); var id = created.path("id").asText();
        assertEquals("NORMAL", created.path("priority").asText()); assertEquals("OPEN", created.path("status").asText());
        var completed = mapper.readTree(mvc.perform(patch("/api/tasks/" + id + "/completion").contentType("application/json")
                .content("{\"completed\":true,\"version\":" + created.path("version") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(1, tasks.count()); assertNotNull(tasks.findById(id).orElseThrow().getCompletedAt());
        assertEquals(1, service.summary("America/Toronto").completed()); assertEquals(0, service.summary("America/Toronto").open());
        mvc.perform(patch("/api/tasks/" + id + "/completion").contentType("application/json")
                .content("{\"completed\":false,\"version\":" + completed.path("version") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        assertNull(tasks.findById(id).orElseThrow().getCompletedAt());
    }
    @Test void stalePageCannotOverwriteCompleteOrDeleteUpdatedTask() throws Exception {
        var created = create("Original"); var id = created.path("id").asText(); var version = created.path("version");
        String revision = service.summary("UTC").version();
        mvc.perform(put("/api/tasks/" + id).contentType("application/json").content("{\"text\":\"Edited\",\"priority\":\"HIGH\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
        assertNotEquals(revision, service.summary("UTC").version());
        mvc.perform(put("/api/tasks/" + id).contentType("application/json").content("{\"text\":\"Stale\",\"version\":" + version + "}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/tasks/" + id + "/completion").contentType("application/json").content("{\"completed\":true,\"version\":" + version + "}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/tasks/" + id).param("version", version.asText())).andExpect(status().isConflict());
        assertEquals("Edited", tasks.findById(id).orElseThrow().getText());
    }
    @Test void invalidReplacementNeverDeletesCurrentDataAndLegacyImportWorks() throws Exception {
        create("Keep me");
        mvc.perform(put("/api/tasks/replace").contentType("application/json")
                .content("[{\"id\":\"valid\",\"text\":\"OK\"},{\"id\":\"invalid\",\"text\":\" \"}]"))
                .andExpect(status().isBadRequest());
        assertEquals("Keep me", service.list().get(0).getText());
        mvc.perform(put("/api/tasks/replace").contentType("application/json").content("[{\"id\":\"legacy-1\",\"text\":\"Old backup\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("OPEN"));
        var old = tasks.findById("legacy-1").orElseThrow(); assertEquals("NORMAL", old.getPriority()); assertEquals(1700000000000L, old.getCreatedAt());
        String before = service.summary("UTC").version();
        mvc.perform(put("/api/tasks/replace").contentType("application/json").content("[{\"id\":\"legacy-1\",\"text\":\"Restored changed content\",\"status\":\"COMPLETED\",\"priority\":\"HIGH\",\"dueAt\":1750000000000,\"completedAt\":1750000001000}]"))
                .andExpect(status().isOk());
        assertEquals(1, tasks.count()); assertEquals(1, service.summary("UTC").completed());
        assertNotEquals(before, service.summary("UTC").version());
        var restored = tasks.findById("legacy-1").orElseThrow(); assertEquals(1750000001000L, restored.getCompletedAt()); assertEquals("HIGH", restored.getPriority());
    }
    @Test void boundChecksRejectMalformedInputAndDuplicateSnapshots() throws Exception {
        for (String json : List.of("{\"text\":\"x\",\"priority\":\"URGENT\"}", "{\"text\":\"x\",\"dueAt\":-1}",
                "{\"text\":\"x\",\"status\":\"DELETED\"}", "{\"text\":\"x\",\"sourceMailId\":1}",
                "{\"text\":\"" + "x".repeat(301) + "\"}"))
            mvc.perform(post("/api/tasks").contentType("application/json").content(json)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/tasks/replace").contentType("application/json").content("[{\"id\":\"same\",\"text\":\"A\"},{\"id\":\"same\",\"text\":\"B\"}]"))
                .andExpect(status().isBadRequest());
        assertEquals(0, tasks.count());
        mvc.perform(get("/api/tasks/summary").param("zone", "Not/A/Timezone")).andExpect(status().isBadRequest());
    }
    long seedSuggestion() {
        var mail = new MailSummary(); mail.setSubject("Synthetic test mail"); mail.setSource("GMAIL"); mail.setExternalId(UUID.randomUUID().toString());
        mail = mails.saveAndFlush(mail);
        var analysis = new MailTaskAnalysis(); analysis.setMailId(mail.getId()); analysis.setFingerprint("test"); analysis.setTasksJson("[{\"id\":\"suggestion-1\",\"title\":\"Synthetic action\"}]");
        analyses.saveAndFlush(analysis); return mail.getId();
    }
    @Test void confirmedSuggestionConversionIsIdempotentAndKeepsUserEdits() throws Exception {
        long mailId = seedSuggestion(); String json = "{\"text\":\"Confirmed task\",\"sourceMailId\":" + mailId + ",\"sourceSuggestionId\":\"suggestion-1\"}";
        var result = mapper.readTree(mvc.perform(post("/api/tasks/from-mail").contentType("application/json").content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String id = result.path("id").asText();
        mvc.perform(put("/api/tasks/" + id).contentType("application/json").content("{\"text\":\"My edited task\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/tasks/" + id + "/completion").contentType("application/json").content("{\"completed\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/from-mail").contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("My edited task")).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals(1, tasks.count()); assertEquals(mailId, tasks.findById(id).orElseThrow().getSourceMailId());
        mvc.perform(post("/api/tasks/from-mail").contentType("application/json").content(json.replace("suggestion-1", "invented")))
                .andExpect(status().isBadRequest());
    }
    @Test void concurrentConversionsReturnOneTask() throws Exception {
        long mailId = seedSuggestion(); var input = new TaskService.Input(null, "Confirmed", null, null, null, null, null, null, null, mailId, "suggestion-1", null);
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<String> call = () -> { start.await(); return controller.fromMail(input).getId(); };
            var a = executor.submit(call); var b = executor.submit(call); start.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)); assertEquals(1, tasks.count());
        } finally { executor.shutdownNow(); }
    }
    @Test void conversionCannotOverwriteAnUnrelatedImportedTaskWithDerivedId() throws Exception {
        long mailId = seedSuggestion();
        String previouslyDerivedId = "mail-" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((mailId + ":suggestion-1").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.create(new TaskService.Input(previouslyDerivedId, "Keep unrelated task", null, "HIGH", null, null, null, null, null, null, null, null));
        var converted = controller.fromMail(new TaskService.Input(null, "Confirmed task", null, null, null, null, null, null, null, mailId, "suggestion-1", null));
        assertNotEquals(previouslyDerivedId, converted.getId()); assertEquals(2, tasks.count());
        var original = tasks.findById(previouslyDerivedId).orElseThrow();
        assertEquals("Keep unrelated task", original.getText()); assertEquals("HIGH", original.getPriority());
        assertNull(original.getSourceMailId()); assertEquals(0L, original.getVersion());
    }
    @Test void summarySeparatesCompletionDeadlineAndCalendarDay() {
        var zone = ZoneId.of("America/Toronto"); long now = System.currentTimeMillis();
        long tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        service.create(new TaskService.Input(null, "Overdue", null, "HIGH", now - 86400000, null, null, null, null, null, null, null));
        service.create(new TaskService.Input(null, "Today", null, null, tomorrow - 1, null, null, null, null, null, null, null));
        service.create(new TaskService.Input(null, "Future", null, null, tomorrow, null, null, null, null, null, null, null));
        service.create(new TaskService.Input(null, "Unscheduled", null, null, null, null, null, null, null, null, null, null));
        service.create(new TaskService.Input(null, "Completed", null, null, now - 86400000, "COMPLETED", now, null, null, null, null, null));
        var summary = service.summary(zone.toString()); assertEquals(4, summary.open()); assertEquals(1, summary.completed());
        assertEquals(1, summary.overdue()); assertEquals(1, summary.dueToday()); assertEquals(1, summary.upcoming()); assertEquals(1, summary.noDeadline());
    }
}
