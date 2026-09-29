package com.smartinbox.processor.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.service.AiService;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailTaskAnalyzerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AiService ai = mock(AiService.class);
    final MailTaskAnalyzer analyzer = new MailTaskAnalyzer(ai);
    @BeforeEach void keepSupportedTasks() throws Exception {
        when(ai.reviewMailTasks(anyString(), anyList())).thenAnswer(call -> {
            List<Map<String,String>> tasks=call.getArgument(1);
            var result=mapper.createArrayNode();
            for(var task:tasks) result.addObject().put("id",task.get("id")).put("priority",task.get("priority")).put("obligation",task.get("obligation"));
            return result;
        });
    }
    static MailSummary mail(String body) {
        var mail = new MailSummary(); mail.setId(1L); mail.setContent(body); mail.setSubject("Assignment");
        mail.setSender("Course team"); mail.setSource("OUTLOOK"); mail.setCreatedTime(LocalDateTime.now()); return mail;
    }
    String response(String evidence, String deadline) throws Exception {
        var result = mapper.createArrayNode();
        result.addObject().put("title", "提交课程作业").put("details", "按邮件要求提交作业。")
                .put("priority", "HIGH").put("obligation", "REQUIRED").put("evidence", evidence).put("deadlineText", deadline);
        return result.toString();
    }
    @Test void longBodyIncludesTailAndKeepsSupportedDeadline() throws Exception {
        var inputs = new ArrayList<String>();
        when(ai.extractMailTasks(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt())).thenAnswer(call -> {
            String body = call.getArgument(3); inputs.add(body);
            return mapper.readTree(body.contains("Submit assignment by Sep 25") ? response("Submit assignment by Sep 25", "Sep 25") : "[]");
        });
        String body = "Context. ".repeat(1000) + "Submit assignment by Sep 25";
        var tasks = analyzer.analyze(mail(body));
        assertTrue(inputs.size() > 3); assertTrue(inputs.get(inputs.size()-1).endsWith("Sep 25"));
        assertEquals(1, tasks.size()); assertEquals("Sep 25", tasks.get(0).deadlineText());
    }
    @Test void unsupportedEvidenceFailsAndGuessedDeadlineIsRemoved() throws Exception {
        when(ai.extractMailTasks(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(mapper.readTree(response("Submit assignment", "September 30, 2026")))
                .thenReturn(mapper.readTree(response("Pay $500 tomorrow", "")));
        assertEquals("", analyzer.analyze(mail("Submit assignment when ready.")).get(0).deadlineText());
        assertThrows(IllegalStateException.class, () -> analyzer.analyze(mail("Submit assignment when ready.")));
    }
    @Test void missingBodyIsNotReportedAsSuccessfullyAnalyzed() {
        assertThrows(IllegalStateException.class, () -> analyzer.analyze(mail(" ")));
        verifyNoInteractions(ai);
    }
    @Test void overlappingChunksDeduplicateTasksAndChineseChunksFitContext() throws Exception {
        when(ai.extractMailTasks(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(mapper.readTree(response("Assignment", "")));
        assertEquals(1, analyzer.analyze(mail("A".repeat(6500))).size());
        String chinese = "这是邮件正文。".repeat(1200);
        var chunks = MailTaskAnalyzer.chunks(chinese);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 1400));
        String joined = chunks.get(0);
        for (int i=1; i<chunks.size(); i++) joined += chunks.get(i).substring(MailTaskAnalyzer.OVERLAP);
        assertEquals(chinese, joined);
    }
    @Test void contentChangesInvalidateCacheButReadAndStarDoNot() {
        var mail = mail("Complete the training."); var fingerprint = MailTaskAnalyzer.fingerprint(mail);
        mail.setInboxRead(true); mail.setStarred(true);
        assertEquals(fingerprint, MailTaskAnalyzer.fingerprint(mail));
        mail.setContent("Complete training by Friday.");
        assertNotEquals(fingerprint, MailTaskAnalyzer.fingerprint(mail));
    }
    @Test void reviewerCanRemovePromotionsButCannotInventNewActions() throws Exception {
        when(ai.extractMailTasks(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt()))
                .thenReturn(mapper.readTree(response("Submit assignment", "")));
        when(ai.reviewMailTasks(anyString(),anyList())).thenReturn(mapper.readTree("[]"))
                .thenReturn(mapper.readTree("[{\"id\":\"invented\",\"obligation\":\"REQUIRED\",\"priority\":\"HIGH\"}]"));
        assertTrue(analyzer.analyze(mail("Submit assignment")).isEmpty());
        assertThrows(IllegalStateException.class,()->analyzer.analyze(mail("Submit assignment")));
    }
}
