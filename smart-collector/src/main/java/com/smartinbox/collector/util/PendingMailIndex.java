package com.smartinbox.collector.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** A published-to-MQ checkpoint is temporary, never proof that a message reached the database. */
public final class PendingMailIndex {
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    private final Path legacyFile;
    private final Path pendingFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> legacy = new HashSet<>();
    private final Map<String, Long> pending = new HashMap<>();
    private final Clock clock;
    public PendingMailIndex(Path legacyFile) { this(legacyFile, Clock.systemUTC()); }
    public PendingMailIndex(Path legacyFile, Clock clock) {
        this.legacyFile = legacyFile; this.pendingFile = legacyFile.resolveSibling(legacyFile.getFileName() + ".pending.json"); this.clock = clock;
        try { if (Files.exists(legacyFile)) legacy.addAll(Files.readAllLines(legacyFile, StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        try {
            Map<String, Long> saved = mapper.readValue(Files.readString(pendingFile), new TypeReference<Map<String, Long>>() {});
            if (saved != null) saved.forEach((id, timestamp) -> {
                if (id != null && !id.isBlank() && timestamp != null && timestamp >= 0 && timestamp <= clock.millis())
                    pending.put(id, timestamp);
            });
        }
        catch (Exception ignored) { /* A broken checkpoint must cause retry, never permanent omission. */ }
    }
    public synchronized boolean recentlyPublished(String id) {
        Long published = pending.get(id);
        long age = published == null ? -1 : clock.millis() - published;
        return published != null && age >= 0 && age < RETRY_AFTER.toMillis();
    }
    public synchronized void published(String id) throws IOException {
        Files.createDirectories(legacyFile.getParent());
        if (legacy.add("html-v1:" + id)) Files.writeString(legacyFile, id + System.lineSeparator() + "html-v1:" + id + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        pending.put(id, clock.millis());
        pending.entrySet().removeIf(entry -> entry.getValue() < clock.millis() - Duration.ofHours(120).toMillis());
        Path temporary = pendingFile.resolveSibling(pendingFile.getFileName() + ".tmp");
        Files.writeString(temporary, mapper.writeValueAsString(pending));
        try { Files.move(temporary, pendingFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, pendingFile, StandardCopyOption.REPLACE_EXISTING); }
    }
}
