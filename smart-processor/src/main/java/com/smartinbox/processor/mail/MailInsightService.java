package com.smartinbox.processor.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.service.AiService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MailInsightService {
    private static final int MAX_BODY = 16000;
    private static final Set<String> ACTIONS = Set.of("REQUIRED", "OPTIONAL", "NONE", "UNCLEAR");
    private final MailSummaryRepository mails;
    private final AiService ai;

    public MailInsightService(MailSummaryRepository mails, AiService ai) {
        this.mails = mails;
        this.ai = ai;
    }

    public Report analyze(Long id) {
        MailSummary mail = mails.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邮件不存在"));
        String plain = MailTaskAnalyzer.plain(mail.getContent());
        String html = MailTaskAnalyzer.plain(mail.getHtmlContent());
        // Some providers supply only a short text preview but a complete HTML body.
        String body = html.length() > plain.length() + 300 && plain.length() < 1200 ? html : plain;
        if (body.isBlank()) body = html;
        body = body.lines().map(String::trim)
                .filter(line -> !line.isBlank() && !line.matches("(?i)^https?://\\S+$")
                        && !line.matches("(?i)^(unsubscribe|privacy policy|view in browser|©.*|copyright.*)$"))
                .collect(Collectors.joining("\n"));
        if (body.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "这封邮件的正文尚未同步，暂时无法分析，请稍后刷新邮件重试");

        boolean truncated = body.length() > MAX_BODY;
        if (truncated) body = body.substring(0, 12500) + "\n[中间过长内容已省略]\n"
                + body.substring(body.length() - 3300);
        try {
            JsonNode result = ai.analyzeMailInsight(MailTaskAnalyzer.subject(mail), mail.getSender(), body, truncated);
            String purpose = chinese(result.path("purpose"), 1200);
            List<String> keyPoints = chineseList(result.path("keyPoints"), 1, 6, 500);
            String status = result.path("actionStatus").asText("").trim();
            if (!ACTIONS.contains(status)) throw new IllegalStateException("Invalid mail insight action");
            String action = chinese(result.path("actionExplanation"), 800);
            List<String> times = chineseList(result.path("importantTimes"), 0, 5, 250);
            List<String> notes = chineseList(result.path("notes"), 0, 4, 400);
            return new Report(purpose, keyPoints, status, action, times, notes,
                    truncated ? "邮件较长：本次分析覆盖开头和结尾，省略了中间部分；请以完整邮件为准。" : "");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件分析已中断，请重试", error);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "本机 AI 分析暂时不可用，请稍后重试", error);
        }
    }

    private static String chinese(JsonNode node, int maximum) {
        String value = node.asText("").trim();
        if (value.isBlank() || value.length() > maximum || value.codePoints().noneMatch(c -> c >= 0x4e00 && c <= 0x9fff))
            throw new IllegalStateException("Invalid Chinese mail insight");
        return value;
    }

    private static List<String> chineseList(JsonNode node, int minimum, int maximum, int itemMaximum) {
        if (minimum == 0 && node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() < minimum || node.size() > maximum)
            throw new IllegalStateException("Invalid mail insight list");
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(chinese(item, itemMaximum)));
        return List.copyOf(result);
    }

    public record Report(String purpose, List<String> keyPoints, String actionStatus,
                         String actionExplanation, List<String> importantTimes, List<String> notes,
                         String scopeNote) { }
}
