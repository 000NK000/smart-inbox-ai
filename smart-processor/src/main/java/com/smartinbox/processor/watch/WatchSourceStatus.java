package com.smartinbox.processor.watch;

import java.time.Instant;

public record WatchSourceStatus(
        String id,
        String name,
        String channel,
        boolean available,
        String status,
        Instant updatedAt,
        int itemCount) {
}
