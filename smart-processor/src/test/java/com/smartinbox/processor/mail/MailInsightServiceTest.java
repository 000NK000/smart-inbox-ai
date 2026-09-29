package com.smartinbox.processor.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.service.AiService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailInsightServiceTest {
    private final MailSummaryRepository mails = mock(MailSummaryRepository.class);
    private final AiService ai = mock(AiService.class);
    private final MailInsightService service = new MailInsightService(mails, ai);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void analyzesFullHtmlBodyWithoutChangingMailState() throws Exception {
        MailSummary mail = new MailSummary(); mail.setId(42L); mail.setOriginalSubject("Immigration events roundup");
        mail.setSender("International Student Experience <mailbox@example.com>");
        mail.setContent("Short text preview");
        mail.setHtmlContent("<div>International students: <b>PGWP information session on September 25, 2026 at 2 p.m.</b> "
                + "Study permit extension workshop on September 27, 2026 at 4 p.m. Register only if interested.</div>".repeat(4));
        mail.setInboxRead(false);
        when(mails.findById(42L)).thenReturn(Optional.of(mail));
        when(ai.analyzeMailInsight(anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(mapper.readTree("""
                        {"purpose":"学校介绍两场面向国际学生的移民信息活动。", "keyPoints":["一场讲毕业工签，另一场讲学签延期。"],
                         "actionStatus":"OPTIONAL", "actionExplanation":"如有兴趣，可以查看活动安排并报名；邮件没有要求必须参加。",
                         "importantTimes":["9月25日下午2点为毕业工签说明会。"], "notes":[]}
                        """));
        var report = service.analyze(42L);
        assertEquals("OPTIONAL", report.actionStatus());
        assertTrue(report.purpose().contains("学校"));
        verify(ai).analyzeMailInsight(eq("Immigration events roundup"), contains("mailbox@example.com"),
                contains("Study permit extension workshop"), eq(false));
        assertFalse(mail.isInboxRead());
        verify(mails, never()).save(any());
    }

    @Test void missingBodyAndMissingMailDoNotCallAi() {
        when(mails.findById(1L)).thenReturn(Optional.empty());
        var absent = assertThrows(ResponseStatusException.class, () -> service.analyze(1L));
        assertEquals(HttpStatus.NOT_FOUND, absent.getStatusCode());
        MailSummary mail = new MailSummary(); mail.setId(2L); mail.setSubject("Only title");
        when(mails.findById(2L)).thenReturn(Optional.of(mail));
        var missing = assertThrows(ResponseStatusException.class, () -> service.analyze(2L));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, missing.getStatusCode());
        verifyNoInteractions(ai);
    }

    @Test void invalidOrNonChineseModelOutputCannotBeShownAsReport() throws Exception {
        MailSummary mail = new MailSummary(); mail.setId(3L); mail.setSubject("Event"); mail.setContent("An optional session.");
        when(mails.findById(3L)).thenReturn(Optional.of(mail));
        when(ai.analyzeMailInsight(anyString(), any(), anyString(), eq(false)))
                .thenReturn(mapper.readTree("""
                        {"purpose":"Optional event", "keyPoints":["可自愿参加。"], "actionStatus":"REQUIRED",
                         "actionExplanation":"必须参加。", "importantTimes":[], "notes":[]}
                        """));
        var invalid = assertThrows(ResponseStatusException.class, () -> service.analyze(3L));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, invalid.getStatusCode());
    }

    @Test void longMailDisclosesThatItsMiddleWasOmitted() throws Exception {
        MailSummary mail = new MailSummary(); mail.setId(4L); mail.setSubject("Long mail");
        mail.setContent("Start notice. " + "Details and context. ".repeat(950) + "Final date September 30.");
        when(mails.findById(4L)).thenReturn(Optional.of(mail));
        when(ai.analyzeMailInsight(anyString(), any(), anyString(), eq(true)))
                .thenReturn(mapper.readTree("""
                        {"purpose":"这是一封较长的通知邮件。", "keyPoints":["邮件提供背景和一个日期。"],
                         "actionStatus":"UNCLEAR", "actionExplanation":"邮件没有明确要求下一步。", "importantTimes":[], "notes":[]}
                        """));
        var report = service.analyze(4L);
        assertTrue(report.scopeNote().contains("省略了中间部分"));
        verify(ai).analyzeMailInsight(anyString(), any(), argThat(body -> body.contains("[中间过长内容已省略]")), eq(true));
    }
}
