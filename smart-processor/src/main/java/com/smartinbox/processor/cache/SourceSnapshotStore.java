package com.smartinbox.processor.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Last successful public source responses; a failed refresh never replaces these files. */
public final class SourceSnapshotStore {
    private static final Logger LOG = LoggerFactory.getLogger(SourceSnapshotStore.class);
    private static final Duration MAX_AGE = Duration.ofDays(7);
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final Path directory;

    public SourceSnapshotStore(ObjectMapper mapper) {
        this(mapper, Path.of(System.getProperty("user.home"), ".smart-inbox", "source-cache"));
    }

    public SourceSnapshotStore(ObjectMapper mapper, Path directory) {
        this.mapper = mapper;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public <T> Optional<Snapshot<T>> load(String key, Class<T> itemType) {
        Path file = file(key);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return Optional.empty();
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (root.path("version").asInt() != 1) return Optional.empty();
            Instant savedAt = Instant.parse(root.path("savedAt").asText());
            Instant now = Instant.now();
            if (savedAt.isBefore(now.minus(MAX_AGE)) || savedAt.isAfter(now.plusSeconds(300))) {
                return Optional.empty();
            }
            JsonNode values = root.path("items");
            if (!values.isArray() || values.isEmpty() || values.size() > 100) return Optional.empty();
            List<T> items = mapper.convertValue(values,
                    mapper.getTypeFactory().constructCollectionType(List.class, itemType));
            return Optional.of(new Snapshot<>(List.copyOf(items), savedAt));
        } catch (Exception error) {
            LOG.warn("Ignoring invalid source snapshot {} ({})", key, error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public <T> void save(String key, List<T> items, Instant savedAt) {
        Path file = file(key);
        if (items == null || items.isEmpty() || items.size() > 100 || savedAt == null) return;
        Path temporary = null;
        try {
            var root = mapper.createObjectNode();
            root.put("version", 1);
            root.put("savedAt", savedAt.toString());
            root.set("items", mapper.valueToTree(items));
            byte[] bytes = mapper.writeValueAsBytes(root);
            if (bytes.length > MAX_BYTES) return;
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, key + "-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            // Caching is optional: disk errors must not hide a successful live response.
            LOG.warn("Unable to save source snapshot {} ({})", key, error.getClass().getSimpleName());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }

    private Path file(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9_-]{0,100}")) {
            throw new IllegalArgumentException("Invalid source cache key");
        }
        return directory.resolve(key + ".json");
    }

    public record Snapshot<T>(List<T> items, Instant savedAt) { }
}
