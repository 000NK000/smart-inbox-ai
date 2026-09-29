package com.smartinbox.processor.trend;

import java.util.List;

/**
 * One platform-specific source for the social trends dashboard.
 *
 * <p>Douyin and Xiaohongshu intentionally use disabled adapters until a
 * permitted, stable data provider is configured.</p>
 */
public interface TrendAdapter {

    String id();

    String name();

    default boolean enabled() {
        return true;
    }

    default String disabledReason() {
        return "";
    }

    List<TrendItem> fetch() throws Exception;
}
