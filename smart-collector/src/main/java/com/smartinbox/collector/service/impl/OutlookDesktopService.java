package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.collector.credentials.CredentialVaultService;
import com.smartinbox.collector.dto.EmailDTO;
import com.smartinbox.collector.service.MailSyncReport;
import com.smartinbox.collector.util.PendingMailIndex;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class OutlookDesktopService {
    private final CredentialVaultService vault;
    private final RocketMQTemplate mq;
    private final ObjectMapper mapper;
    @Autowired private MailSyncIndex syncIndex;
    @Autowired private com.smartinbox.collector.runtime.CollectorShutdownSignal shutdown = new com.smartinbox.collector.runtime.CollectorShutdownSignal();
    private Path processedFile;
    private PendingMailIndex pending;
    private final ReentrantLock syncLock = new ReentrantLock();
    private volatile String lastAttempt = "", lastSuccess = "";
    private volatile MailSyncReport report = MailSyncReport.failed("desktop", "not_started");
    public OutlookDesktopService(CredentialVaultService vault, RocketMQTemplate mq, ObjectMapper mapper) {
        this.vault = vault; this.mq = mq; this.mapper = mapper;
    }
    @PostConstruct void initialize() {
        if (processedFile == null) processedFile = Path.of(System.getProperty("user.home"), ".smart-inbox", "processed_outlook_desktop_emails.txt");
        pending = new PendingMailIndex(processedFile);
    }
    private PendingMailIndex pending() { if (pending == null) initialize(); return pending; }
    public MailSyncReport report() { return report; }
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", Files.exists(scriptPath())); result.put("state", report.state());
        result.put("syncing", syncLock.isLocked()); result.put("lastAttempt", lastAttempt); result.put("lastSuccess", lastSuccess);
        result.put("published", report.published()); result.put("scanned", report.scanned());
        result.put("bodyFetched", report.bodyFetched()); result.put("skipped", report.skipped());
        result.put("failureCode", report.failureCode()); result.put("reconciled", report.reconciled()); return result;
    }
    public boolean fetchRecentEmails() {
        if (!syncLock.tryLock()) return false;
        lastAttempt = Instant.now().toString();
        int scanned = 0, skipped = 0, fetched = 0, published = 0;
        boolean reconciled = false;
        try {
            shutdown.check();
            pending();
            var durable = syncIndex == null ? MailSyncIndex.Snapshot.unavailable() : syncIndex.snapshot("OUTLOOK");
            reconciled = durable.available();
            Instant cutoff = Instant.now().minus(Duration.ofHours(com.smartinbox.collector.util.MailWindow.HOURS));
            var metadata = runBridge(true, List.of(), cutoff);
            if (!"connected".equals(metadata.state())) { report = MailSyncReport.failed("desktop", metadata.state()); return false; }
            Map<String, String> selected = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : metadata.messages()) {
                shutdown.check();
                if (Instant.parse(item.path("receivedTime").asText()).isBefore(cutoff)) continue;
                String id = externalId(item);
                if (!seen.add(id)) continue;
                scanned++;
                if (durable.complete(id) || pending().recentlyPublished(id)) { skipped++; continue; }
                String entryId = item.path("entryId").asText("");
                if (entryId.isBlank()) throw new IllegalArgumentException("Missing desktop entry ID");
                selected.put(entryId, id);
            }
            if (!selected.isEmpty()) {
                shutdown.check();
                var full = runBridge(false, List.copyOf(selected.keySet()), cutoff);
                if (!"connected".equals(full.state())) { report = MailSyncReport.failed("desktop", full.state()); return false; }
                Set<String> returned = new HashSet<>();
                for (var item : full.messages()) {
                    shutdown.check();
                    String entry = item.path("entryId").asText();
                    if (!selected.containsKey(entry) || !selected.get(entry).equals(externalId(item))) throw new IllegalStateException("Bridge identity mismatch");
                    returned.add(entry); fetched++;
                    published += publishMessages(mapper.createArrayNode().add(item));
                }
                if (returned.size() != selected.size()) {
                    report = new MailSyncReport(false, "failed", "desktop", scanned, published, fetched, skipped, "messages_changed_during_sync", reconciled);
                    return false;
                }
            }
            lastSuccess = Instant.now().toString();
            report = new MailSyncReport(true, "connected", "desktop", scanned, published, fetched, skipped, "", reconciled); return true;
        } catch (java.util.concurrent.CancellationException error) {
            report = MailSyncReport.failed("desktop", "collector_stopping"); return false;
        } catch (Exception error) {
            report = new MailSyncReport(false, "failed", "desktop", scanned, published, fetched, skipped, "desktop_sync_failed", reconciled); return false;
        } finally { syncLock.unlock(); }
    }
    int publishMessages(JsonNode messages) throws Exception {
        int sent = 0;
        for (JsonNode message : messages) {
            shutdown.check();
            String id = externalId(message);
            if (pending().recentlyPublished(id)) continue;
            if (!message.has("body") && !message.has("htmlBody")) throw new IllegalArgumentException("Full body missing");
            var dto = new EmailDTO(message.path("subject").asText("(No subject)"), message.path("sender").asText(""), message.path("body").asText(""),
                    Instant.parse(message.path("receivedTime").asText()).toEpochMilli(), "OUTLOOK", id);
            dto.setHtmlContent(message.path("htmlBody").asText(""));
            mq.convertAndSend("EMAIL_RAW_TOPIC", dto); pending().published(id); sent++;
        }
        return sent;
    }
    static String externalId(JsonNode message) {
        String stable = message.path("internetMessageId").asText("").trim();
        if (stable.isBlank()) stable = message.path("entryId").asText("").trim();
        if (stable.isBlank()) throw new IllegalArgumentException("Missing Outlook message identity");
        return DigestUtils.md5DigestAsHex(stable.getBytes(StandardCharsets.UTF_8));
    }
    ScriptResult runBridge(boolean metadataOnly, List<String> entryIds, Instant cutoff) {
        shutdown.check();
        if (!Files.exists(scriptPath())) return new ScriptResult("bridge_missing", mapper.createArrayNode());
        Path requestFile = null;
        try {
            List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath().toString(),
                    "-Email", vault.value("outlook.username", ""), "-SinceIso", cutoff.toString(), "-MetadataOnly", Boolean.toString(metadataOnly)));
            if (!metadataOnly) {
                Files.createDirectories(processedFile.getParent());
                requestFile = Files.createTempFile(processedFile.getParent(), "outlook-ids-", ".json");
                Files.writeString(requestFile, mapper.writeValueAsString(entryIds), StandardCharsets.UTF_8);
                command.addAll(List.of("-RequestedIdsFile", requestFile.toString()));
            }
            String output = com.smartinbox.collector.util.BridgeProcess.capture(new ProcessBuilder(command), processedFile.getParent(), Duration.ofSeconds(90), shutdown::isStopping);
            int start = output.indexOf('{');
            if (start < 0) return new ScriptResult("unavailable", mapper.createArrayNode());
            JsonNode json = mapper.readTree(output.substring(start));
            if (!json.path("messages").isArray()) return new ScriptResult("invalid_response", mapper.createArrayNode());
            return new ScriptResult(json.path("state").asText("unavailable"), json.path("messages"));
        } catch (java.util.concurrent.CancellationException error) { throw error; }
        catch (java.util.concurrent.TimeoutException error) { return new ScriptResult("timeout", mapper.createArrayNode()); }
        catch (Exception error) { return new ScriptResult("unavailable", mapper.createArrayNode()); }
        finally { if (requestFile != null) try { Files.deleteIfExists(requestFile); } catch (Exception ignored) { } }
    }
    private Path scriptPath() { return Path.of(System.getProperty("user.dir"), "scripts", "read-outlook-mail.ps1"); }
    record ScriptResult(String state, JsonNode messages) {}
}
