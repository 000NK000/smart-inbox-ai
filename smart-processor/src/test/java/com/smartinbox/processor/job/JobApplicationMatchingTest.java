package com.smartinbox.processor.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.JobMailLink;
import com.smartinbox.processor.entity.JobMailSuggestion;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.*;
import com.smartinbox.processor.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = {
        "spring.cloud.bootstrap.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
}, showSql = false)
@ContextConfiguration(classes = JobApplicationMatchingTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobApplicationMatchingTest {
    private static final String PREPARATIONS = "[{\"title\":\"Prepare examples\",\"details\":\"Review synthetic project notes.\",\"priority\":\"HIGH\"}]";

    @Configuration
    @EntityScan(basePackageClasses = MailSummary.class)
    @EnableJpaRepositories(basePackageClasses = MailSummaryRepository.class)
    @Import({JobApplicationService.class, TaskService.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JobApplicationService service;
    @Autowired JobApplicationRepository applications;
    @SpyBean JobMailSuggestionRepository suggestions;
    @Autowired JobMailLinkRepository links;
    @Autowired MailSummaryRepository mails;
    @Autowired TaskItemRepository tasks;
    @Autowired ObjectMapper mapper;
    @MockBean JobMailAnalyzer analyzer;
    @SpyBean TaskService taskService;

    @BeforeEach
    void clean() {
        links.deleteAll();
        suggestions.deleteAll();
        applications.deleteAll();
        tasks.deleteAll();
        mails.deleteAll();
        clearInvocations(taskService);
    }

    @Test
    void identicalRoleNeverMatchesACompanyThatDiffers() {
        application("XYZ", "Developer", "APPLIED");
        var suggestion = suggestion("ABC", "Developer");

        assertNull(matchedApplication(suggestion));
    }

    @Test
    void singleExactCompanyWinsOverAnUnrelatedExactRole() {
        application("XYZ", "Developer", "APPLIED");
        var abc = application("ABC", "Backend Engineer", "APPLIED");

        assertEquals(abc.id(), matchedApplication(suggestion("ABC", "Developer")));
    }

    @Test
    void typoDoesNotFallBackToAnotherCompanyWithTheSameRole() {
        application("ABC", "Backend Engineer", "APPLIED");
        application("XYZ", "Developer", "APPLIED");

        assertNull(matchedApplication(suggestion("ADC", "Developer")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Unknown", "未知公司"})
    void unknownCompaniesRemainUnmatched(String company) {
        application("XYZ", "Developer", "APPLIED");

        assertNull(matchedApplication(suggestion(company, "Developer")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b.c.", "ABC Inc.", "A.B.C. Corp.", "abc ltd"})
    void companyMatchingIgnoresCasePunctuationAndLegalSuffixes(String company) {
        var abc = application("ABC", "Developer", "APPLIED");

        assertEquals(abc.id(), matchedApplication(suggestion(company, "Developer")));
    }

    @Test
    void acronymSubstringIsNotACompanyMatch() {
        application("EXYZ Solutions", "Developer", "APPLIED");

        assertNull(matchedApplication(suggestion("XYZ", "Developer")));
    }

    @ParameterizedTest
    @CsvSource({
            "XYZ Graduate Program 2030, XYZ",
            "ABC Canada, ABC",
            "XYZ, XYZ Graduate Program 2030",
            "ABC, ABC Canada"
    })
    void recognizableLeadingBrandMatchesAndAppliesWithoutLosingProgramLabel(String applicationCompany,
                                                                           String suggestionCompany) {
        var application = application(applicationCompany, "Developer", "APPLIED");
        var suggestion = suggestion(suggestionCompany, "Developer");

        assertEquals(application.id(), matchedApplication(suggestion));
        var result = service.applySuggestion(suggestion.getMailId(),
                new JobApplicationService.ApplyInput(application.id(), application.version(), suggestion.getVersion(), List.of(0)));

        assertEquals(applicationCompany, result.application().company());
        assertEquals("INTERVIEW", result.application().stage());
        assertEquals(1, result.tasksCreated());
        assertTrue(links.existsById(linkId(application.id(), suggestion.getMailId())));
    }

    @ParameterizedTest
    @CsvSource({
            "XYZapital, XYZ",
            "ABCatic, ABC",
            "Example ABC, ABC",
            "Bank Toronto, Bank",
            "GE Canada, GE"
    })
    void partialInteriorGenericOrShortBrandCannotMatchOrApply(String applicationCompany,
                                                              String suggestionCompany) {
        var application = application(applicationCompany, "Developer", "APPLIED");
        var suggestion = suggestion(suggestionCompany, "Developer");
        var before = state();

        assertNull(matchedApplication(suggestion));
        assertStatus(400, () -> service.applySuggestion(suggestion.getMailId(),
                new JobApplicationService.ApplyInput(application.id(), application.version(), suggestion.getVersion(), List.of(0))));

        assertEquals(before, state());
    }

    @Test
    void vagueRoleCannotChooseBetweenSameCompanyApplications() {
        application("ABC", "Backend Developer", "APPLIED");
        application("ABC", "Frontend Developer", "APPLIED");

        assertNull(matchedApplication(suggestion("ABC", "Developer")));
    }

    @Test
    void multipleSameCompanyApplicationsRequireOneExactNormalizedRole() {
        var backend = application("ABC", "Backend Developer", "APPLIED");
        application("ABC", "Frontend Developer", "APPLIED");
        application("XYZ", "Backend Developer", "APPLIED");

        assertEquals(backend.id(), matchedApplication(suggestion("A.B.C.", "backend-developer")));
    }

    @Test
    void tiedExactRolesRemainUnmatched() {
        application("ABC", "Backend Developer", "APPLIED");
        application("ABC Inc.", "Backend-Developer", "APPLIED");

        assertNull(matchedApplication(suggestion("ABC", "Backend Developer")));
    }

    @Test
    void applyRejectsKnownCompanyMismatchWithoutChangingAnyPersistedState() {
        var xyz = application("XYZ", "Developer", "APPLIED");
        var suggestion = suggestion("ABC", "Developer");
        var before = state();

        assertStatus(400, () -> service.applySuggestion(suggestion.getMailId(),
                new JobApplicationService.ApplyInput(xyz.id(), xyz.version(), suggestion.getVersion(), List.of(0))));

        assertEquals(before, state());
        verify(taskService, never()).fromJobSuggestion(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Unknown", "未知公司"})
    void applyAllowsManualSelectionWhenCompanyIsUnknown(String company) {
        var xyz = application("XYZ", "Developer", "APPLIED");
        var suggestion = suggestion(company, "Developer");

        var result = service.applySuggestion(suggestion.getMailId(),
                new JobApplicationService.ApplyInput(xyz.id(), xyz.version(), suggestion.getVersion(), List.of(0)));

        assertEquals("INTERVIEW", result.application().stage());
        assertEquals(1, result.tasksCreated());
        assertEquals(xyz.id(), suggestions.findById(suggestion.getMailId()).orElseThrow().getApplicationId());
        assertTrue(links.existsById(linkId(xyz.id(), suggestion.getMailId())));
    }

    static Stream<List<Integer>> invalidIndexes() {
        return Stream.of(List.of(0, -1), List.of(0, 1), Arrays.asList(0, null));
    }

    @ParameterizedTest
    @MethodSource("invalidIndexes")
    void applyValidatesEverySelectionBeforeConvertingAnyTask(List<Integer> selected) {
        var abc = application("ABC", "Developer", "APPLIED");
        var suggestion = suggestion("ABC", "Developer");
        var before = state();

        assertStatus(400, () -> service.applySuggestion(suggestion.getMailId(),
                new JobApplicationService.ApplyInput(abc.id(), abc.version(), suggestion.getVersion(), selected)));

        assertEquals(before, state());
        verify(taskService, never()).fromJobSuggestion(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"\",\"details\":\"Synthetic preparation\",\"priority\":\"NORMAL\"}",
            "{\"title\":\"Review notes\",\"details\":\"Synthetic preparation\",\"priority\":\"INVALID\"}"
    })
    void applyValidatesAllSelectedTaskContentsBeforeConvertingAnyTask(String invalidPreparation) {
        var abc = application("ABC", "Developer", "APPLIED");
        var suggestion = suggestion("ABC", "Developer");
        suggestion.setPreparationsJson(PREPARATIONS.substring(0, PREPARATIONS.length() - 1) + "," + invalidPreparation + "]");
        var saved = suggestions.saveAndFlush(suggestion);
        var before = state();

        assertStatus(400, () -> service.applySuggestion(saved.getMailId(),
                new JobApplicationService.ApplyInput(abc.id(), abc.version(), saved.getVersion(), List.of(0, 1))));

        assertEquals(before, state());
        verify(taskService, never()).fromJobSuggestion(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void moveRepairsWrongCompanyLinkWithoutChangingStagesOrTasks() {
        var source = application("XYZ", "Developer", "RESULT");
        var target = application("ABC", "Backend Engineer", "PREPARING");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        var unrelatedMail = mail();
        link(source.id(), unrelatedMail.getId());
        var task = taskService.fromJobSuggestion(suggestion.getMailId(), "job:" + suggestion.getMailId() + ":0:synthetic", "Existing preparation", "HIGH", "Keep these notes.");
        var taskBefore = json(task);

        var result = service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version()));

        assertEquals(source.id(), result.source().id());
        assertEquals(target.id(), result.target().id());
        assertEquals(source.version() + 1, result.source().version());
        assertEquals(target.version() + 1, result.target().version());
        assertPipelinePreserved(source, result.source());
        assertPipelinePreserved(target, result.target());
        assertEquals(List.of(unrelatedMail.getId()), result.source().linkedMails().stream().map(JobApplicationService.MailView::id).toList());
        assertEquals(List.of(suggestion.getMailId()), result.target().linkedMails().stream().map(JobApplicationService.MailView::id).toList());
        assertFalse(links.existsById(linkId(source.id(), suggestion.getMailId())));
        assertTrue(links.existsById(linkId(target.id(), suggestion.getMailId())));
        var savedSuggestion = suggestions.findById(suggestion.getMailId()).orElseThrow();
        assertEquals("APPLIED", savedSuggestion.getStatus());
        assertEquals(target.id(), savedSuggestion.getApplicationId());
        assertEquals(1, tasks.count());
        assertEquals(taskBefore, json(tasks.findById(task.getId()).orElseThrow()));
    }

    @Test
    void moveRequiresBothCurrentVersions() {
        var source = application("ABC", "Developer", "APPLIED");
        var target = application("ABC", "Backend Engineer", "APPLIED");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        var before = state();

        assertStatus(409, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version() + 1, target.version())));
        assertStatus(409, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version() + 1)));
        assertStatus(400, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), null, target.version())));
        assertStatus(400, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), null)));

        assertEquals(before, state());
    }

    @Test
    void moveRejectsTargetThatDisagreesWithKnownSuggestionCompany() {
        var source = application("XYZ", "Developer", "APPLIED");
        var target = application("XYZ", "Backend Engineer", "APPLIED");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        var before = state();

        assertStatus(400, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version())));

        assertEquals(before, state());
    }

    @Test
    void moveWithUnknownCompanyAllowsExplicitTargetAndLeavesOpenSuggestionOpen() {
        var source = application("XYZ", "Developer", "APPLIED");
        var target = application("ABC", "Backend Engineer", "APPLIED");
        var suggestion = suggestion("Unknown", "Developer");
        link(source.id(), suggestion.getMailId());
        var suggestionBefore = json(suggestions.findById(suggestion.getMailId()).orElseThrow());

        service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version()));

        assertTrue(links.existsById(linkId(target.id(), suggestion.getMailId())));
        assertEquals(suggestionBefore, json(suggestions.findById(suggestion.getMailId()).orElseThrow()));
    }

    @Test
    void moveRequiresExistingSourceLinkAndDifferentExistingTarget() {
        var source = application("ABC", "Developer", "APPLIED");
        var target = application("ABC", "Backend Engineer", "APPLIED");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        var before = state();

        assertStatus(404, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version())));
        assertEquals(before, state());
        link(source.id(), suggestion.getMailId());
        before = state();

        assertStatus(400, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(source.id(), source.version(), source.version())));
        assertStatus(404, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput("missing-application", source.version(), 0L)));
        assertEquals(before, state());
    }

    @Test
    void moveRejectsDuplicateTargetLinkWithoutRemovingSource() {
        var source = application("ABC", "Developer", "APPLIED");
        var target = application("ABC", "Backend Engineer", "APPLIED");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        link(target.id(), suggestion.getMailId());
        var before = state();

        assertStatus(409, () -> service.moveMail(source.id(), suggestion.getMailId(),
                new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version())));

        assertEquals(before, state());
    }

    @ParameterizedTest(name = "move = {0}: late suggestion failure rolls back application and link writes")
    @ValueSource(booleans = {true, false})
    void moveAndUnlinkRollBackAllWritesWhenSuggestionPersistenceFails(boolean move) {
        var source = application("ABC", "Developer", "APPLIED");
        var target = application("ABC", "Backend Engineer", "PREPARING");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        taskService.fromJobSuggestion(suggestion.getMailId(), "job:" + suggestion.getMailId() + ":0:synthetic",
                "Existing preparation", "NORMAL", "Keep these notes.");
        var before = state();
        var failure = new IllegalStateException("Synthetic late persistence failure");
        doThrow(failure).when(suggestions).saveAndFlush(any(JobMailSuggestion.class));

        try {
            var caught = assertThrows(IllegalStateException.class, () -> {
                if (move) {
                    service.moveMail(source.id(), suggestion.getMailId(),
                            new JobApplicationService.MailLinkInput(target.id(), source.version(), target.version()));
                } else {
                    service.unlinkMail(source.id(), suggestion.getMailId(), source.version());
                }
            });
            assertSame(failure, caught);
            assertEquals(before, state());
        } finally {
            reset(suggestions);
        }
    }

    @Test
    void unlinkLastAssociationReopensAppliedSuggestionAndPreservesPipelineAndTasks() {
        var source = application("ABC", "Developer", "RESULT");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        var task = taskService.fromJobSuggestion(suggestion.getMailId(), "job:" + suggestion.getMailId() + ":0:synthetic", "Existing preparation", "NORMAL", "Keep these notes.");
        var taskBefore = json(task);

        var result = service.unlinkMail(source.id(), suggestion.getMailId(), source.version());

        assertEquals(source.version() + 1, result.version());
        assertPipelinePreserved(source, result);
        assertTrue(result.linkedMails().isEmpty());
        assertEquals(0, links.count());
        var saved = suggestions.findById(suggestion.getMailId()).orElseThrow();
        assertEquals("OPEN", saved.getStatus());
        assertNull(saved.getApplicationId());
        assertEquals(1, tasks.count());
        assertEquals(taskBefore, json(tasks.findById(task.getId()).orElseThrow()));
    }

    @Test
    void unlinkRepointsAppliedSuggestionWhenAnotherAssociationRemains() {
        var source = application("ABC", "Developer", "APPLIED");
        var remaining = application("ABC", "Backend Engineer", "APPLIED");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        link(remaining.id(), suggestion.getMailId());
        var remainingBefore = json(applications.findById(remaining.id()).orElseThrow());

        service.unlinkMail(source.id(), suggestion.getMailId(), source.version());

        var saved = suggestions.findById(suggestion.getMailId()).orElseThrow();
        assertEquals("APPLIED", saved.getStatus());
        assertEquals(remaining.id(), saved.getApplicationId());
        assertEquals(1, links.count());
        assertTrue(links.existsById(linkId(remaining.id(), suggestion.getMailId())));
        assertEquals(remainingBefore, json(applications.findById(remaining.id()).orElseThrow()));
    }

    @Test
    void unlinkDoesNotReopenAnUnrelatedDismissedSuggestion() {
        var source = application("ABC", "Developer", "APPLIED");
        var suggestion = suggestion("ABC", "Developer");
        suggestion.setStatus("DISMISSED");
        suggestions.saveAndFlush(suggestion);
        link(source.id(), suggestion.getMailId());
        var before = json(suggestions.findById(suggestion.getMailId()).orElseThrow());

        service.unlinkMail(source.id(), suggestion.getMailId(), source.version());

        assertEquals(before, json(suggestions.findById(suggestion.getMailId()).orElseThrow()));
    }

    @Test
    void unlinkRequiresCurrentVersionAndAnExistingLink() {
        var source = application("ABC", "Developer", "APPLIED");
        var suggestion = appliedSuggestion("ABC", "Developer", source.id());
        link(source.id(), suggestion.getMailId());
        var before = state();

        assertStatus(409, () -> service.unlinkMail(source.id(), suggestion.getMailId(), source.version() + 1));
        assertStatus(400, () -> service.unlinkMail(source.id(), suggestion.getMailId(), null));
        assertStatus(404, () -> service.unlinkMail(source.id(), suggestion.getMailId() + 100000, source.version()));

        assertEquals(before, state());
    }

    private JobApplicationService.ApplicationView application(String company, String role, String stage) {
        return service.create(new JobApplicationService.Input(company, role, stage,
                "RESULT".equals(stage) ? "REJECTED" : null, "Waterloo", "https://example.com/jobs/synthetic",
                "Keep synthetic application notes.", 1700000000000L, 1800000000000L, null));
    }

    private MailSummary mail() {
        var value = new MailSummary();
        value.setSource("OUTLOOK");
        value.setExternalId("synthetic-" + UUID.randomUUID());
        value.setSubject("Synthetic interview invitation");
        value.setOriginalSubject("Synthetic interview invitation");
        value.setSender("Recruiting <recruiting@example.com>");
        value.setContent("Synthetic recruitment message used only by regression tests.");
        value.setCreatedTime(LocalDateTime.of(2026, 1, 1, 12, 0));
        return mails.saveAndFlush(value);
    }

    private JobMailSuggestion suggestion(String company, String role) {
        var value = new JobMailSuggestion();
        value.setMailId(mail().getId());
        value.setFingerprint("abcdefabcdefabcdefabcdefabcdefab");
        value.setRecruitment(true);
        value.setCompany(company);
        value.setRole(role);
        value.setSuggestedStage("INTERVIEW");
        value.setSuggestedResult("");
        value.setSummaryChinese("Synthetic interview invitation.");
        value.setEvidence("Synthetic recruitment evidence.");
        value.setPreparationsJson(PREPARATIONS);
        value.setStatus("OPEN");
        value.setAnalyzedAt(Instant.parse("2026-01-01T12:00:00Z"));
        return suggestions.saveAndFlush(value);
    }

    private JobMailSuggestion appliedSuggestion(String company, String role, String applicationId) {
        var value = suggestion(company, role);
        value.setStatus("APPLIED");
        value.setApplicationId(applicationId);
        return suggestions.saveAndFlush(value);
    }

    private void link(String applicationId, Long mailId) {
        var value = new JobMailLink();
        value.setId(linkId(applicationId, mailId));
        value.setApplicationId(applicationId);
        value.setMailId(mailId);
        value.setLinkedAt(1700000000000L);
        links.saveAndFlush(value);
    }

    private String linkId(String applicationId, Long mailId) { return applicationId + ":" + mailId; }

    private String matchedApplication(JobMailSuggestion suggestion) {
        return service.overview().suggestions().stream()
                .filter(value -> value.mailId().equals(suggestion.getMailId()))
                .findFirst().orElseThrow().matchedApplicationId();
    }

    private void assertPipelinePreserved(JobApplicationService.ApplicationView before,
                                         JobApplicationService.ApplicationView after) {
        assertAll(
                () -> assertEquals(before.company(), after.company()),
                () -> assertEquals(before.role(), after.role()),
                () -> assertEquals(before.stage(), after.stage()),
                () -> assertEquals(before.result(), after.result()),
                () -> assertEquals(before.location(), after.location()),
                () -> assertEquals(before.jobUrl(), after.jobUrl()),
                () -> assertEquals(before.notes(), after.notes()),
                () -> assertEquals(before.appliedAt(), after.appliedAt()),
                () -> assertEquals(before.nextActionAt(), after.nextActionAt()),
                () -> assertEquals(before.createdAt(), after.createdAt()));
    }

    private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    private State state() {
        return new State(snapshot(applications.findAll()), snapshot(suggestions.findAll()),
                snapshot(links.findAll()), snapshot(tasks.findAll()));
    }

    private List<String> snapshot(List<?> values) { return values.stream().map(this::json).sorted().toList(); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new AssertionError("Cannot snapshot synthetic test state", error); }
    }

    private record State(List<String> applications, List<String> suggestions, List<String> links, List<String> tasks) {}
}
