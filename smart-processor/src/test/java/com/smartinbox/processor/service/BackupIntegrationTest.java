package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.job.*;
import com.smartinbox.processor.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"}, showSql=false)
@ContextConfiguration(classes = BackupIntegrationTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackupIntegrationTest {
    @Configuration @EntityScan(basePackageClasses = MailSummary.class)
    @EnableJpaRepositories(basePackageClasses = MailSummaryRepository.class)
    @Import({BackupService.class, TaskService.class, CalendarService.class, JobApplicationService.class})
    static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); } }
    @Autowired BackupService backups;
    @SpyBean TaskService taskService;
    @SpyBean CalendarService calendarService;
    @MockBean JobMailAnalyzer jobMailAnalyzer;
    @Autowired TaskItemRepository tasks;
    @Autowired WatchListRepository watch;
    @Autowired MailSummaryRepository mails;
    @Autowired AppPreferenceRepository settings;
    @Autowired CalendarEventRepository calendar;
    @Autowired JobApplicationRepository jobApplications;
    @Autowired JobMailLinkRepository jobLinks;
    @Autowired JobMailSuggestionRepository jobSuggestions;
    @Autowired FocusSessionRepository focusSessions;
    @Autowired PracticeProgressRepository practice;
    @Autowired PracticeGroupRepository practiceGroups;
    @Autowired PracticeSolutionRepository practiceSolutions;
    @Autowired PracticeSolutionImageRepository practiceImages;
    @Autowired ObjectMapper mapper;
    @BeforeEach void clean() { practiceImages.deleteAll();practiceSolutions.deleteAll();practiceGroups.deleteAll();practice.deleteAll();focusSessions.deleteAll();jobLinks.deleteAll();jobSuggestions.deleteAll();jobApplications.deleteAll();tasks.deleteAll(); watch.deleteAll(); mails.deleteAll(); settings.deleteAll(); calendar.deleteAll(); }
    MailSummary mail() {
        var m = new MailSummary(); m.setSource("GMAIL"); m.setExternalId("backup-synthetic-message"); m.setSubject("Synthetic");
        m.setContent("NEVER_EXPORT_RAW_MAIL_BODY"); m.setHtmlContent("NEVER_EXPORT_RAW_HTML"); m.setCreatedTime(LocalDateTime.now());
        m.setStarred(true); m.setSnoozedUntil(System.currentTimeMillis()+3600000); return mails.saveAndFlush(m);
    }
    void seedTask(Long mailId) {
        taskService.importTasks(List.of(new TaskService.Input("synthetic-task", "Finish report", 1700000000000L, "HIGH", null, "OPEN", null, null, "Notes", mailId, "suggestion-a", null)), false);
    }
    void seedWatch() {
        var w = new WatchListEntry(); w.setId("synthetic-watch"); w.setTitle("Synthetic Show"); w.setTitleKey("synthetic show"); w.setKind("TV");
        w.setStatus("WATCHING"); w.setCurrentEpisode(2); w.setTotalEpisodes(8); w.setCreatedAt(1700000000000L); w.setUpdatedAt(1700000000000L);
        watch.saveAndFlush(w);
    }
    CalendarService.Definition seedCalendar() {
        return calendarService.create(new CalendarService.Input("CS lecture", "COURSE", "2026-09-21T09:30", "2026-09-21T10:50",
                "America/Toronto", "WEEKLY", List.of(1, 3, 5), "2026-12-04", "MC 2065", "Bring lecture notes", null));
    }
    String checksum(Object payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(payload)));
    }
    @Test void practiceProgressRoundtripsAndOldSchemaFourRemainsValid() throws Exception {
        var row=new PracticeProgress(); row.setNumber(525);row.setTitle("连续数组");row.setTopic("前缀和");row.setStatus("YELLOW");
        row.setAttempts(2);row.setGreenCount(0);row.setYellowCount(1);row.setRedCount(1);row.setTotalMinutes(83);
        row.setReviewStage(2);row.setLastPracticedAt(1770000000000L);row.setNextReviewAt(1770600000000L);
        row.setMistake("METHOD");row.setNote("前缀和没有想到");practice.saveAndFlush(row);
        var exported=backups.export();
        assertEquals(7,exported.schema());
        assertEquals(1,backups.validate(exported).practice());
        practice.deleteAll();
        backups.restore(mapper.readValue(mapper.writeValueAsBytes(exported),BackupService.Envelope.class));
        assertEquals(83,practice.findById(525).orElseThrow().getTotalMinutes());
        assertEquals("前缀和没有想到",practice.findById(525).orElseThrow().getNote());
        var existing=practice.findById(525).orElseThrow();existing.setNote("后续手动更新");practice.saveAndFlush(existing);
        backups.restore(exported);
        assertEquals("后续手动更新",practice.findById(525).orElseThrow().getNote());

        var p=exported.payload();
        var schemaFourPayload=new LinkedHashMap<String,Object>();
        schemaFourPayload.put("tasks",p.tasks());schemaFourPayload.put("watchlist",p.watchlist());schemaFourPayload.put("mailStates",p.mailStates());
        schemaFourPayload.put("settings",p.settings());schemaFourPayload.put("calendar",p.calendar());schemaFourPayload.put("jobApplications",p.jobApplications());
        schemaFourPayload.put("focusSessions",p.focusSessions());schemaFourPayload.put("rescheduleCounts",p.rescheduleCounts());
        var old=mapper.readValue(mapper.writeValueAsBytes(Map.of("schema",4,"createdAt",exported.createdAt(),
                "checksum",checksum(schemaFourPayload),"payload",schemaFourPayload)),BackupService.Envelope.class);
        assertEquals(0,backups.validate(old).practice());
        backups.restore(old);
        assertEquals("后续手动更新",practice.findById(525).orElseThrow().getNote());
    }
    @Test void focusSessionsAndRescheduleCountsSurviveBackupRestore() throws Exception {
        var message=mail(); seedTask(message.getId());
        var task=tasks.findById("synthetic-task").orElseThrow(); task.setRescheduleCount(2); tasks.saveAndFlush(task);
        var session=new FocusSession(); session.setId(UUID.randomUUID().toString()); session.setCategory("FRENCH");
        session.setTaskId(task.getId()); session.setStartedAt(System.currentTimeMillis()-60000); session.setEndedAt(System.currentTimeMillis());
        focusSessions.saveAndFlush(session);
        settings.saveAndFlush(new AppPreference("entertainmentDailyLimitMinutes","300"));
        var file=mapper.readValue(mapper.writeValueAsBytes(backups.export()),BackupService.Envelope.class);
        assertEquals(7,file.schema()); assertEquals(1,file.payload().focusSessions().size());
        focusSessions.deleteAll(); tasks.deleteAll(); settings.deleteAll();
        backups.restore(file);
        assertEquals(1,focusSessions.count());
        assertEquals(2,tasks.findById(task.getId()).orElseThrow().getRescheduleCount());
        assertEquals("300",settings.findById("entertainmentDailyLimitMinutes").orElseThrow().getValue());
    }
    @Test void twoStateFocusBackupKeepsConfirmedTimeWithoutRuntimeLease() throws Exception {
        long now=System.currentTimeMillis();
        var effective=new FocusSession();effective.setId(UUID.randomUUID().toString());effective.setCategory("EFFECTIVE");
        effective.setStartedAt(now-180000);effective.setLastHeartbeatAt(now-120000);effective.setRuntimeId("test-runtime");
        var ineffective=new FocusSession();ineffective.setId(UUID.randomUUID().toString());ineffective.setCategory("INEFFECTIVE");
        ineffective.setStartedAt(now-300000);ineffective.setEndedAt(now-240000);
        focusSessions.saveAllAndFlush(List.of(effective,ineffective));
        String json=mapper.writeValueAsString(backups.export());
        assertFalse(json.contains("test-runtime"));assertFalse(json.contains("lastHeartbeatAt"));
        var file=mapper.readValue(json,BackupService.Envelope.class);
        assertEquals(now-120000,file.payload().focusSessions().stream().filter(s->s.getCategory().equals("EFFECTIVE")).findFirst().orElseThrow().getEndedAt());
        focusSessions.deleteAll();backups.restore(file);
        assertEquals(Set.of("EFFECTIVE","INEFFECTIVE"),focusSessions.findAll().stream().map(FocusSession::getCategory).collect(java.util.stream.Collectors.toSet()));
        assertTrue(focusSessions.findAll().stream().allMatch(s->s.getEndedAt()!=null&&s.getRuntimeId()==null&&s.getLastHeartbeatAt()==null));
    }
    @Test void groupNotesRoundtripAndLegacySchemaFiveRemainValid() throws Exception {
        var group = new PracticeGroup(); group.setName("动态规划"); group.setNote("先定义状态和转移"); practiceGroups.saveAndFlush(group);
        var exported = backups.export();
        assertEquals(7, exported.schema());
        assertEquals(1, backups.validate(exported).practiceGroups());
        practiceGroups.deleteAll();
        backups.restore(mapper.readValue(mapper.writeValueAsBytes(exported), BackupService.Envelope.class));
        assertEquals("先定义状态和转移", practiceGroups.findById("动态规划").orElseThrow().getNote());
        var edited = practiceGroups.findById("动态规划").orElseThrow(); edited.setNote("我后来的修改"); practiceGroups.saveAndFlush(edited);
        backups.restore(exported);
        assertEquals("我后来的修改", practiceGroups.findById("动态规划").orElseThrow().getNote());

        var p = exported.payload();
        var legacy = new LinkedHashMap<String, Object>();
        legacy.put("tasks", p.tasks()); legacy.put("watchlist", p.watchlist()); legacy.put("mailStates", p.mailStates());
        legacy.put("settings", p.settings()); legacy.put("calendar", p.calendar()); legacy.put("jobApplications", p.jobApplications());
        legacy.put("focusSessions", p.focusSessions()); legacy.put("rescheduleCounts", p.rescheduleCounts()); legacy.put("practice", p.practice());
        var old = mapper.readValue(mapper.writeValueAsBytes(Map.of("schema", 5, "createdAt", exported.createdAt(),
                "checksum", checksum(legacy), "payload", legacy)), BackupService.Envelope.class);
        assertEquals(0, backups.validate(old).practiceGroups());
        backups.restore(old);
        assertEquals("我后来的修改", practiceGroups.findById("动态规划").orElseThrow().getNote());
    }
    @Test void illustratedSolutionsRoundtripAndSchemaSixRemainsValid() throws Exception {
        var solution = new PracticeSolution();solution.setNumber(525);solution.setContent("前缀和题解");solution.setUpdatedAt(1770000000000L);practiceSolutions.saveAndFlush(solution);
        var screenshot = new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3};
        var image = new PracticeSolutionImage();image.setId(UUID.randomUUID().toString());image.setProblemNumber(525);
        image.setMimeType("image/png");image.setCaption("图解");image.setPosition(0);image.setData(screenshot);practiceImages.saveAndFlush(image);
        var file = mapper.readValue(mapper.writeValueAsBytes(backups.export()),BackupService.Envelope.class);
        assertEquals(7,file.schema());assertEquals(1,backups.validate(file).practiceSolutions());assertEquals(1,backups.validate(file).practiceImages());
        practiceImages.deleteAll();practiceSolutions.deleteAll();
        backups.restore(file);
        assertEquals("前缀和题解",practiceSolutions.findById(525).orElseThrow().getContent());
        assertArrayEquals(screenshot,practiceImages.findById(image.getId()).orElseThrow().getData());
        var edited=practiceSolutions.findById(525).orElseThrow();edited.setContent("后续个人修改");practiceSolutions.saveAndFlush(edited);
        backups.restore(file);
        assertEquals("后续个人修改",practiceSolutions.findById(525).orElseThrow().getContent());

        var p=file.payload();var legacy=new LinkedHashMap<String,Object>();
        legacy.put("tasks",p.tasks());legacy.put("watchlist",p.watchlist());legacy.put("mailStates",p.mailStates());
        legacy.put("settings",p.settings());legacy.put("calendar",p.calendar());legacy.put("jobApplications",p.jobApplications());
        legacy.put("focusSessions",p.focusSessions());legacy.put("rescheduleCounts",p.rescheduleCounts());
        legacy.put("practice",p.practice());legacy.put("practiceGroups",p.practiceGroups());
        var old=mapper.readValue(mapper.writeValueAsBytes(Map.of("schema",6,"createdAt",file.createdAt(),
                "checksum",checksum(legacy),"payload",legacy)),BackupService.Envelope.class);
        assertEquals(0,backups.validate(old).practiceSolutions());
        backups.restore(old);
        assertEquals("后续个人修改",practiceSolutions.findById(525).orElseThrow().getContent());
    }
    @Test void serializedRoundtripRestoresAndRemapsMailIdsWithoutExportingBodies() throws Exception {
        var original = mail(); seedTask(original.getId()); seedWatch(); var course = seedCalendar(); settings.saveAndFlush(new AppPreference("mailBlockAd", "true"));
        String json = mapper.writeValueAsString(backups.export());
        assertFalse(json.contains("NEVER_EXPORT")); assertFalse(json.contains("titleKey"));
        var file = mapper.readValue(json, BackupService.Envelope.class);
        assertEquals(7, file.schema()); assertEquals(List.of(course), file.payload().calendar());
        assertEquals(1, backups.validate(file).matchedMails());
        assertEquals(1, backups.validate(file).calendar());
        tasks.deleteAll(); watch.deleteAll(); settings.deleteAll(); mails.deleteAll(); calendar.deleteAll();
        var replacement = mail(); assertNotEquals(original.getId(), replacement.getId());
        replacement.setStarred(false); replacement.setSnoozedUntil(null); mails.saveAndFlush(replacement);
        var restored = backups.restore(file);
        assertEquals(1, restored.tasks()); assertEquals(1, restored.watchlist()); assertEquals(1, restored.calendar());
        assertEquals(List.of(course), calendarService.exportDefinitions());
        assertEquals(replacement.getId(), tasks.findById("synthetic-task").orElseThrow().getSourceMailId());
        assertEquals("synthetic show", watch.findById("synthetic-watch").orElseThrow().getTitleKey());
        assertTrue(mails.findById(replacement.getId()).orElseThrow().isStarred());
        assertEquals("true", settings.findById("mailBlockAd").orElseThrow().getValue());
        // Reimporting preserves edits to existing ids and calendar records added after the snapshot.
        var task = tasks.findById("synthetic-task").orElseThrow(); task.setText("User edited after restore"); tasks.saveAndFlush(task);
        var editedCourse = calendar.findById(course.id()).orElseThrow(); editedCourse.setTitle("Updated lecture"); calendar.saveAndFlush(editedCourse);
        var newCourse = seedCalendar();
        backups.restore(mapper.readValue(json, BackupService.Envelope.class));
        assertEquals(1, tasks.count()); assertEquals(1, watch.count()); assertEquals(2, calendar.count());
        assertEquals("User edited after restore", tasks.findById("synthetic-task").orElseThrow().getText());
        assertEquals("Updated lecture", calendar.findById(course.id()).orElseThrow().getTitle());
        assertTrue(calendar.existsById(newCourse.id()));
    }
    @Test void legacySchemaOneChecksumRestoresOriginalDataAndPreservesCalendar() throws Exception {
        var original = mail(); seedTask(original.getId()); seedWatch(); var course = seedCalendar();
        settings.saveAndFlush(new AppPreference("mailBlockAd", "true"));
        var exported = backups.export(); var p = exported.payload();
        // Independently recreate the exact original schema, with no calendar property at all.
        var legacyPayload = new LinkedHashMap<String, Object>();
        legacyPayload.put("tasks", p.tasks()); legacyPayload.put("watchlist", p.watchlist());
        legacyPayload.put("mailStates", p.mailStates()); legacyPayload.put("settings", p.settings());
        String json = mapper.writeValueAsString(Map.of("schema", 1, "createdAt", exported.createdAt(),
                "checksum", checksum(legacyPayload), "payload", legacyPayload));
        assertFalse(json.contains("\"calendar\""));
        var legacy = mapper.readValue(json, BackupService.Envelope.class);
        assertNull(legacy.payload().calendar()); assertEquals(0, backups.validate(legacy).calendar());
        tasks.deleteAll(); watch.deleteAll(); settings.deleteAll();
        original.setStarred(false); mails.saveAndFlush(original);
        backups.restore(legacy);
        assertEquals(1, tasks.count()); assertEquals(1, watch.count());
        assertTrue(mails.findById(original.getId()).orElseThrow().isStarred());
        assertEquals("true", settings.findById("mailBlockAd").orElseThrow().getValue());
        assertEquals(List.of(course), calendarService.exportDefinitions());
        // Schema 1 may not smuggle in calendar data that its legacy checksum does not cover.
        var mixedSchema = new BackupService.Envelope(1, legacy.createdAt(), legacy.checksum(), p);
        assertThrows(ResponseStatusException.class, () -> backups.restore(mixedSchema));
        assertEquals(List.of(course), calendarService.exportDefinitions());
    }
    @Test void invalidCalendarInChecksummedBackupChangesNoPersonalData() throws Exception {
        var original = mail(); seedTask(original.getId()); seedWatch(); var course = seedCalendar();
        settings.saveAndFlush(new AppPreference("mailBlockAd", "true"));
        var file = backups.export(); var p = file.payload();
        var invalid = new CalendarService.Definition(UUID.randomUUID().toString(), "Invalid course", "COURSE",
                "2026-09-21T10:00", "2026-09-21T09:00", "America/Toronto", "NONE", List.of(), null, "", "", null);
        var payload = new BackupService.Payload(p.tasks(), p.watchlist(), p.mailStates(), p.settings(), List.of(course, invalid), p.jobApplications(), p.focusSessions(), p.rescheduleCounts(), p.practice());
        var signed = new BackupService.Envelope(5, file.createdAt(), checksum(payload), payload);
        tasks.deleteAll(); watch.deleteAll(); original.setStarred(false); mails.saveAndFlush(original);
        settings.saveAndFlush(new AppPreference("mailBlockAd", "false"));
        assertThrows(ResponseStatusException.class, () -> backups.restore(signed));
        assertEquals(0, tasks.count()); assertEquals(0, watch.count());
        assertFalse(mails.findById(original.getId()).orElseThrow().isStarred());
        assertEquals("false", settings.findById("mailBlockAd").orElseThrow().getValue());
        assertEquals(List.of(course), calendarService.exportDefinitions());
    }
    @Test void calendarRestoreFailureRollsBackAllPersonalData() {
        var original = mail(); seedTask(original.getId()); seedWatch(); seedCalendar();
        settings.saveAndFlush(new AppPreference("mailBlockAd", "true")); var file = backups.export();
        tasks.deleteAll(); watch.deleteAll(); calendar.deleteAll();
        original.setStarred(false); mails.saveAndFlush(original);
        settings.saveAndFlush(new AppPreference("mailBlockAd", "false"));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Injected failure after calendar records were persisted");
        }).when(calendarService).restoreBackup(anyList());
        assertThrows(IllegalStateException.class, () -> backups.restore(file));
        assertEquals(0, tasks.count()); assertEquals(0, watch.count()); assertEquals(0, calendar.count());
        assertFalse(mails.findById(original.getId()).orElseThrow().isStarred());
        assertEquals("false", settings.findById("mailBlockAd").orElseThrow().getValue());
    }
    @Test void tamperedChecksumFailsBeforeAnyStateChanges() {
        var m = mail(); seedTask(m.getId()); var file = backups.export();
        m.setStarred(false); mails.saveAndFlush(m);
        var tampered = new BackupService.Envelope(file.schema(), file.createdAt(), "invalid-checksum", file.payload());
        assertThrows(ResponseStatusException.class, () -> backups.restore(tampered));
        assertFalse(mails.findById(m.getId()).orElseThrow().isStarred()); assertEquals(1, tasks.count());
    }
    @Test void restorationFailureRollsBackAlreadyUpdatedMailState() {
        var m = mail(); seedTask(m.getId()); var file = backups.export();
        m.setStarred(false); mails.saveAndFlush(m);
        doThrow(new IllegalStateException("Injected task restore failure")).when(taskService).importTasks(anyList(), eq(false));
        assertThrows(IllegalStateException.class, () -> backups.restore(file));
        assertFalse(mails.findById(m.getId()).orElseThrow().isStarred());
        assertEquals(1, tasks.count());
    }
}
