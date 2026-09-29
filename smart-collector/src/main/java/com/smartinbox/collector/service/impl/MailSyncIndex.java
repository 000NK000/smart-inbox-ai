package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Reconcile transport checkpoints with the processor's durable database, without loading bodies. */
@Service
public class MailSyncIndex {
    @Value("${smart.processor.base-url:http://127.0.0.1:8083}")
    private String baseUrl = "http://127.0.0.1:8083";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public Snapshot snapshot(String source) {
        if (!Set.of("GMAIL", "QQMAIL", "OUTLOOK").contains(source)) throw new IllegalArgumentException("Unknown source");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/mails/sync-index?source=" + source))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Snapshot.unavailable();
            var json = mapper.readTree(response.body());
            if (!json.path("complete").asBoolean() || !json.path("items").isArray()) return Snapshot.unavailable();
            Set<String> complete = new HashSet<>();
            for (var item : json.path("items")) {
                String id = item.path("externalId").asText("");
                if (!id.isBlank() && item.path("bodyAvailable").asBoolean()) complete.add(id);
            }
            return new Snapshot(true, Set.copyOf(complete));
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return Snapshot.unavailable(); }
        catch (Exception error) { return Snapshot.unavailable(); }
    }
    public record Snapshot(boolean available, Set<String> completeIds) {
        public static Snapshot unavailable() { return new Snapshot(false, Set.of()); }
        public boolean complete(String id) { return available && completeIds.contains(id); }
    }
}
