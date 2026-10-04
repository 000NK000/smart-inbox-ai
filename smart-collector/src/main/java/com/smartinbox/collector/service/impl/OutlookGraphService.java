package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.collector.dto.EmailDTO;
import com.smartinbox.collector.service.MailSyncReport;
import com.smartinbox.collector.util.PendingMailIndex;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class OutlookGraphService {
    private final OutlookOAuthService oauth;
    private final RocketMQTemplate mq;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ReentrantLock lock = new ReentrantLock();
    @Autowired private MailSyncIndex syncIndex;
    @Autowired private com.smartinbox.collector.runtime.CollectorShutdownSignal shutdown = new com.smartinbox.collector.runtime.CollectorShutdownSignal();
    private Path processedFile;
    private PendingMailIndex pending;
    private volatile MailSyncReport report = MailSyncReport.failed("oauth", "not_started");
    public OutlookGraphService(OutlookOAuthService oauth, RocketMQTemplate mq, ObjectMapper mapper) {
        this.oauth = oauth; this.mq = mq; this.mapper = mapper;
    }
    @PostConstruct void initialize() {
        if (processedFile == null) processedFile = Path.of(System.getProperty("user.home"), ".smart-inbox", "processed_outlook_emails.txt");
        pending = new PendingMailIndex(processedFile);
    }
    private PendingMailIndex pending() { if (pending == null) initialize(); return pending; }
    public MailSyncReport report() { return report; }

    public boolean fetchRecentEmails() {
        if (!lock.tryLock()) return false;
        int scanned = 0, published = 0, fetched = 0, skipped = 0;
        boolean reconciled = false;
        try {
            shutdown.check();
            Optional<String> token = oauth.accessToken();
            if (token.isEmpty()) { report = MailSyncReport.failed("oauth", "authorization_required"); return false; }
            var durable = syncIndex == null ? MailSyncIndex.Snapshot.unavailable() : syncIndex.snapshot("OUTLOOK");
            reconciled = durable.available();
            Instant cutoff = Instant.now().minus(Duration.ofHours(com.smartinbox.collector.util.MailWindow.HOURS));
            // Metadata first; no body/preview in collection pages, no isRead filtering.
            String url = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?$filter="
                    + encode("receivedDateTime ge " + cutoff) + "&$select=id,internetMessageId,receivedDateTime"
                    + "&$orderby=receivedDateTime%20desc&$top=100";
            Set<String> pages = new HashSet<>(), seen = new HashSet<>();
            do {
                shutdown.check();
                validateGraphUrl(url);
                if (!pages.add(url)) throw new IllegalStateException("repeated_continuation");
                JsonNode page = readGraph(url, token.get());
                if (!page.path("value").isArray()) throw new IllegalStateException("invalid_metadata");
                for (JsonNode metadata : page.path("value")) {
                    shutdown.check();
                    if (Instant.parse(metadata.path("receivedDateTime").asText()).isBefore(cutoff)) continue;
                    String id = externalId(metadata);
                    if (!seen.add(id)) continue;
                    scanned++;
                    if (durable.complete(id) || pending().recentlyPublished(id)) { skipped++; continue; }
                    String graphId = metadata.path("id").asText("");
                    if (graphId.isBlank()) throw new IllegalStateException("missing_message_id");
                    JsonNode message = readGraph("https://graph.microsoft.com/v1.0/me/messages/" + encode(graphId)
                            + "?$select=id,internetMessageId,subject,from,body,receivedDateTime", token.get());
                    fetched++;
                    if (!externalId(message).equals(id)) throw new IllegalStateException("message_identity_changed");
                    published += publishMessages(mapper.createArrayNode().add(message));
                }
                url = page.path("@odata.nextLink").asText("");
            } while (!url.isBlank());
            report = new MailSyncReport(true, "connected", "oauth", scanned, published, fetched, skipped, "", reconciled);
            return true;
        } catch (java.util.concurrent.CancellationException error) {
            report = MailSyncReport.failed("oauth", "collector_stopping"); return false;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            report = new MailSyncReport(false, "failed", "oauth", scanned, published, fetched, skipped,
                    error instanceof GraphHttpException httpError ? "graph_http_" + httpError.status : "graph_sync_failed", reconciled);
            return false;
        } finally { lock.unlock(); }
    }

    /** Overridable transport for fixtures; no response bodies/tokens in logs. */
    JsonNode readGraph(String url, String token) throws Exception {
        shutdown.check();
        validateGraphUrl(url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("Prefer", "outlook.body-content-type=\"html\"").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new GraphHttpException(response.statusCode());
        return mapper.readTree(response.body());
    }
    static void validateGraphUrl(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"graph.microsoft.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !uri.getPath().startsWith("/v1.0/")) throw new IllegalArgumentException("Invalid Graph URL");
    }
    static String externalId(JsonNode message) {
        String stable = message.path("internetMessageId").asText("").trim();
        if (stable.isBlank()) stable = message.path("id").asText("").trim();
        if (stable.isBlank()) throw new IllegalArgumentException("Missing Outlook identity");
        return DigestUtils.md5DigestAsHex(stable.getBytes(StandardCharsets.UTF_8));
    }
    int publishMessages(JsonNode messages) throws Exception {
        int sent = 0;
        for (var message : messages) {
            shutdown.check();
            String id = externalId(message);
            if (pending().recentlyPublished(id)) continue;
            if (!message.path("body").has("content")) throw new IllegalArgumentException("Full body missing");
            var from = message.path("from").path("emailAddress");
            String name = from.path("name").asText(), address = from.path("address").asText();
            var dto = new EmailDTO(message.path("subject").asText("(No subject)"), name.isBlank() ? address : name + " <" + address + ">",
                    message.path("body").path("content").asText(), Instant.parse(message.path("receivedDateTime").asText()).toEpochMilli(), "OUTLOOK", id);
            if ("html".equalsIgnoreCase(message.path("body").path("contentType").asText())) dto.setHtmlContent(message.path("body").path("content").asText());
            mq.convertAndSend("EMAIL_RAW_TOPIC", dto);
            pending().published(id); sent++;
        }
        return sent;
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static class GraphHttpException extends Exception { final int status; GraphHttpException(int status) { this.status = status; } }
}
