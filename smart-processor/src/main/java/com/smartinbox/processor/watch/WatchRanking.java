package com.smartinbox.processor.watch;

import java.util.List;

public record WatchRanking(String id, String name, List<WatchItem> items, List<WatchCollection> collections) {
    public WatchRanking(String id, String name, List<WatchItem> items) {
        this(id, name, items, List.of());
    }
}
