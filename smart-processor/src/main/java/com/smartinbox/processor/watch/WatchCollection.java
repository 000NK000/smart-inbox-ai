package com.smartinbox.processor.watch;

import java.util.List;

public record WatchCollection(String id, String name, String title, String kind, String url,
                              List<WatchItem> items, WatchSourceStatus status) {}
