package com.smartinbox.processor.watch;

import java.time.Instant;
import java.util.List;

public record WatchOverview(
        List<WatchRanking> movies,
        List<WatchRanking> tvShows,
        List<WatchSourceStatus> sources,
        Instant updatedAt) {
}
