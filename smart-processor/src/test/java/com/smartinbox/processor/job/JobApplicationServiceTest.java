package com.smartinbox.processor.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.repository.*;
import com.smartinbox.processor.service.TaskService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties={"spring.cloud.bootstrap.enabled=false","spring.cloud.nacos.discovery.enabled=false","spring.cloud.nacos.config.enabled=false"},showSql=false)
@ContextConfiguration(classes=JobApplicationServiceTest.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class JobApplicationServiceTest {
    @Configuration @EntityScan(basePackageClasses=MailSummary.class) @EnableJpaRepositories(basePackageClasses=MailSummaryRepository.class)
    @Import({JobApplicationService.class,TaskService.class}) static class Config{@Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}}
    @Autowired JobApplicationService service; @Autowired JobApplicationRepository applications; @Autowired JobMailSuggestionRepository suggestions;
    @Autowired JobMailLinkRepository links; @Autowired MailSummaryRepository mails; @Autowired TaskItemRepository tasks;
    @MockBean JobMailAnalyzer analyzer;
    @BeforeEach void clean(){links.deleteAll();suggestions.deleteAll();applications.deleteAll();tasks.deleteAll();mails.deleteAll();}
    JobApplicationService.Input input(String stage,String result,Long version){return new JobApplicationService.Input("Example Inc.","Backend Engineer",stage,result,"Waterloo","https://example.com/jobs/1","Keep notes",1700000000000L,1800000000000L,version);}

    @Test void createUpdateAndStaleWriteProtectionPreservePipelineData(){
        var created=service.create(input("PREPARING",null,null));assertEquals(0L,created.version());assertEquals(1L,service.overview().stageCounts().get("PREPARING"));
        var updated=service.update(created.id(),input("APPLIED",null,created.version()));assertEquals("APPLIED",updated.stage());assertEquals(1L,updated.version());
        ResponseStatusException stale=assertThrows(ResponseStatusException.class,()->service.update(created.id(),input("INTERVIEW",null,0L)));assertEquals(409,stale.getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->service.create(input("RESULT",null,null)));
    }

    @Test void confirmedInterviewLinksMailAdvancesStageAndCreatesOnlySelectedTasks() throws Exception {
        var application=service.create(input("APPLIED",null,null));
        var mail=new MailSummary();mail.setSource("OUTLOOK");mail.setExternalId("job-mail-1");mail.setSubject("Interview invitation");mail.setOriginalSubject("Interview invitation");mail.setSender("Recruiting");mail.setContent("We invite you to an interview.");mail.setCreatedTime(LocalDateTime.now());mail=mails.saveAndFlush(mail);
        var suggestion=new JobMailSuggestion();suggestion.setMailId(mail.getId());suggestion.setFingerprint("abcdefabcdefabcd");suggestion.setRecruitment(true);suggestion.setCompany("Example Inc.");suggestion.setRole("Backend Engineer");suggestion.setSuggestedStage("INTERVIEW");suggestion.setSuggestedResult("");suggestion.setSummaryChinese("公司邀请你参加面试。");suggestion.setEvidence("We invite you to an interview.");suggestion.setPreparationsJson("[{\"title\":\"确认面试安排\",\"details\":\"核对时间与会议入口。\",\"priority\":\"HIGH\"},{\"title\":\"准备岗位案例\",\"details\":\"整理项目经历。\",\"priority\":\"NORMAL\"}]");suggestion.setStatus("OPEN");suggestion.setAnalyzedAt(Instant.now());suggestion=suggestions.saveAndFlush(suggestion);
        var applied=service.applySuggestion(mail.getId(),new JobApplicationService.ApplyInput(application.id(),application.version(),suggestion.getVersion(),List.of(0)));
        assertEquals("INTERVIEW",applied.application().stage());assertEquals(1,applied.tasksCreated());assertEquals(1,applied.application().linkedMails().size());assertEquals(mail.getId(),applied.application().linkedMails().get(0).id());
        var task=tasks.findAll().get(0);assertEquals("确认面试安排",task.getText());assertEquals(mail.getId(),task.getSourceMailId());assertTrue(task.getSourceSuggestionId().startsWith("job:"));
        assertEquals("APPLIED",suggestions.findById(mail.getId()).orElseThrow().getStatus());
    }
}
