package com.smartinbox.collector.service;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Shared guard for both scheduled and manual ingestion; operational metadata only. */
@Service
public class SourceHealthService {
    public static final List<String> SOURCES = List.of("GMAIL", "QQMAIL", "OUTLOOK");
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    public SourceHealthService() { for (String source : SOURCES) channels.put(source, new Channel(source)); }
    public static String source(String value) {
        String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(source)) throw new IllegalArgumentException("Unknown mail source");
        return source;
    }
    public synchronized boolean begin(String source) {
        Channel channel = channels.get(source(source));
        if (channel.inFlight) return false;
        channel.inFlight = true; channel.lastAttempt = Instant.now().toString(); return true;
    }
    public synchronized void finish(String source, MailSyncReport report) {
        Channel channel = channels.get(source(source));
        channel.report = report;
        if (report.success()) channel.lastSuccess = Instant.now().toString();
        channel.inFlight = false;
    }
    public synchronized List<Map<String, Object>> snapshots() { return channels.values().stream().map(Channel::snapshot).toList(); }
    private static class Channel {
        final String source; boolean inFlight; String lastAttempt = "", lastSuccess = "";
        MailSyncReport report = new MailSyncReport(false, "idle", "", 0, 0, 0, 0, "", false);
        Channel(String source) { this.source = source; }
        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", source); result.put("state", inFlight ? "syncing" : report.state());
            result.put("lastAttempt", lastAttempt); result.put("lastSuccess", lastSuccess); result.put("inFlight", inFlight);
            result.put("scanned", report.scanned()); result.put("published", report.published()); result.put("bodyFetched", report.bodyFetched());
            result.put("skipped", report.skipped()); result.put("failureCode", report.failureCode()); result.put("mode", report.mode());
            result.put("reconciled", report.reconciled()); return result;
        }
    }
}
