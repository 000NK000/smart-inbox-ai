package com.smartinbox.processor.trend;

import java.util.List;

public class PendingTrendAdapter implements TrendAdapter {

    private final String id;
    private final String name;
    private final String reason;

    public PendingTrendAdapter(String id, String name, String reason) {
        this.id = id;
        this.name = name;
        this.reason = reason;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String disabledReason() {
        return reason;
    }

    @Override
    public List<TrendItem> fetch() {
        return List.of();
    }
}
