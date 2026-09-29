package com.smartinbox.processor.trend;

import java.time.Instant;
import java.util.List;

public record TrendPlatform(
        String id,
        String name,
        boolean enabled,
        boolean available,
        String status,
        Instant updatedAt,
        List<TrendItem> items) {
}
