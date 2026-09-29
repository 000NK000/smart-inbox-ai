package com.smartinbox.processor.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.service.AiService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobMailAnalyzerTest {
    final AiService ai=mock(AiService.class);
    final JobMailAnalyzer analyzer=new JobMailAnalyzer(ai);
    final ObjectMapper mapper=new ObjectMapper();
    MailSummary mail(String subject,String body){MailSummary m=new MailSummary();m.setId(7L);m.setSubject(subject);m.setOriginalSubject(subject);m.setSender("Recruiting <jobs@example.com>");m.setContent(body);m.setSummary(body);m.setCreatedTime(LocalDateTime.now());return m;}

    @Test void extractsEvidencedInterviewAndChinesePreparationItems() throws Exception {
        var response=mapper.readTree("""
          {"recruitment":true,"company":"Example","role":"Software Engineer","stage":"INTERVIEW","result":"","summaryChinese":"公司邀请你参加软件工程师岗位面试。","evidenceLine":1,"preparations":[{"title":"确认面试安排","details":"核对时间、时区和会议入口。","priority":"HIGH"}]}
        """);
        when(ai.analyzeJobMail(anyString(),anyString(),anyString(),anyList())).thenReturn(response);
        var result=analyzer.analyze(mail("Interview invitation","We would like to invite you to an interview next week."));
        assertTrue(result.recruitment());assertEquals("INTERVIEW",result.stage());assertEquals("We would like to invite you to an interview next week.",result.evidence());assertEquals("确认面试安排",result.preparations().get(0).title());
    }

    @Test void rejectsInventedEvidenceAndInvalidResultStage() throws Exception {
        when(ai.analyzeJobMail(anyString(),anyString(),anyString(),anyList())).thenReturn(mapper.readTree("""
          {"recruitment":true,"company":"X","role":"Y","stage":"INTERVIEW","result":"OFFER","summaryChinese":"收到面试邀请。","evidenceLine":99,"preparations":[]}
        """));
        assertThrows(IllegalStateException.class,()->analyzer.analyze(mail("Interview","Invitation")));
    }

    @Test void filtersGenericJobAlertsBeforeCallingAi() {
        assertFalse(analyzer.likely(mail("Your weekly job alert","Recommended jobs for you")));
        assertTrue(analyzer.likely(mail("Application update: coding assessment","Please complete the online test")));
        verifyNoInteractions(ai);
    }
}
