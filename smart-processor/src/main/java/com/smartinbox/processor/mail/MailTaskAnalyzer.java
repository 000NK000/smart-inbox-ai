package com.smartinbox.processor.mail;

import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.service.AiService;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MailTaskAnalyzer {
    static final int CHUNK_SIZE = 3000, OVERLAP = 250;
    private final AiService ai;
    public MailTaskAnalyzer(AiService ai) { this.ai = ai; }

    public List<Suggestion> analyze(MailSummary mail) throws Exception {
        String body = body(mail);
        if (body.isBlank()) throw new IllegalStateException("Mail body is missing");
        String subject = subject(mail);
        List<String> chunks = chunks(body);
        Map<String, Suggestion> result = new LinkedHashMap<>();
        for (int index = 0; index < chunks.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String chunk = chunks.get(index);
            var tasks = ai.extractMailTasks(subject, safe(mail.getSender()), String.valueOf(mail.getCreatedTime()), chunk, index + 1, chunks.size());
            String evidenceSource = normalize(subject + "\n" + chunk);
            for (var node : tasks) {
                String title = node.path("title").asText("").trim(), details = node.path("details").asText("").trim();
                String evidence = node.path("evidence").asText("").trim();
                if (!chinese(title) || !chinese(details) || title.length() > 150 || details.length() > 600
                        || evidence.length() < 4 || evidence.length() > 500 || !evidenceSource.contains(normalize(evidence)))
                    throw new IllegalStateException("AI task lacks supported evidence");
                String deadline = node.path("deadlineText").asText("").trim();
                if (deadline.length() > 150 || (!deadline.isBlank() && !evidenceSource.contains(normalize(deadline)))) deadline = "";
                String priority = node.path("priority").asText("NORMAL"), obligation = node.path("obligation").asText("OPTIONAL");
                if (!Set.of("HIGH", "NORMAL", "LOW").contains(priority)) priority = "NORMAL";
                if (!Set.of("REQUIRED", "OPTIONAL").contains(obligation)) obligation = "OPTIONAL";
                String key = normalize(title).replaceAll("[\\p{P}\\s]", "");
                Suggestion suggestion = new Suggestion(hash(mail.getId() + ":" + key), title, details, priority, obligation, deadline, evidence);
                // Overlapping chunks must not multiply the same action; prefer a version with a deadline.
                var previous = result.get(key);
                if (previous == null || (previous.deadlineText().isBlank() && !deadline.isBlank())) result.put(key, suggestion);
            }
        }
        // Review in bounded groups: the reviewer can only select existing, evidenced actions.
        List<Suggestion> candidates = new ArrayList<>(result.values()), reviewed = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += 6) {
            Map<String, Suggestion> batch = new LinkedHashMap<>();
            for (var task : candidates.subList(start, Math.min(start + 6, candidates.size()))) batch.put(task.id(), task);
            var input = batch.values().stream().map(task -> Map.of("id", task.id(), "title", task.title(),
                    "details", task.details(), "evidence", task.evidence(), "deadline", task.deadlineText(),
                    "obligation", task.obligation(), "priority", task.priority())).toList();
            var keep = ai.reviewMailTasks(subject, input);
            Set<String> selected = new HashSet<>();
            for (var node : keep) {
                String id = node.path("id").asText();
                if (!batch.containsKey(id)) throw new IllegalStateException("AI review selected an unknown task");
                if (!selected.add(id)) continue;
                var task = batch.get(id);
                String obligation = node.path("obligation").asText(), priority = node.path("priority").asText();
                if (!Set.of("REQUIRED", "OPTIONAL").contains(obligation) || !Set.of("HIGH", "NORMAL", "LOW").contains(priority))
                    throw new IllegalStateException("Invalid task review labels");
                reviewed.add(new Suggestion(id, task.title(), task.details(), priority, obligation, task.deadlineText(), task.evidence()));
            }
        }
        return List.copyOf(reviewed);
    }

    static boolean chinese(String value) { return value.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff); }
    static String normalize(String value) { return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    static String safe(String value) { return value == null ? "" : value; }
    public static String subject(MailSummary mail) {
        return plain(mail.getOriginalSubject() == null || mail.getOriginalSubject().isBlank() ? mail.getSubject() : mail.getOriginalSubject());
    }
    public static String body(MailSummary mail) {
        String value = safe(mail.getContent());
        if (value.isBlank()) value = safe(mail.getHtmlContent());
        return plain(value);
    }
    static String plain(String value) {
        return HtmlUtils.htmlUnescape(safe(value).replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(?:br|/p|/div|/tr|/li)[^>]*>", "\n").replaceAll("<[^>]*>", " "))
                .replace('\u00a0', ' ').replaceAll("[\\t ]+", " ").replaceAll("\\n\\s*\\n", "\n").trim();
    }
    public static String fingerprint(MailSummary mail) {
        return hash("mail-plan-v3\n" + subject(mail) + "\n" + safe(mail.getSender()) + "\n" + mail.getCreatedTime() + "\n" + body(mail));
    }
    static String hash(String value) { return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8)); }
    static List<String> chunks(String body) {
        List<String> result = new ArrayList<>();
        // Leave room for the prompt and JSON response in the local model's context.
        long cjk = body.codePoints().filter(c -> c >= 0x4e00 && c <= 0x9fff).count();
        int chunkSize = cjk > body.length() / 5 ? 1400 : CHUNK_SIZE;
        for (int start = 0; start < body.length();) {
            int end = Math.min(start + chunkSize, body.length());
            if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
            result.add(body.substring(start, end));
            if (end == body.length()) break;
            start = end - OVERLAP;
            if (Character.isLowSurrogate(body.charAt(start))) start--;
        }
        return result;
    }
    public record Suggestion(String id, String title, String details, String priority, String obligation,
                             String deadlineText, String evidence) { }
}
