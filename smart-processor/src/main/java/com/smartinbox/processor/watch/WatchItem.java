package com.smartinbox.processor.watch;

public record WatchItem(
        String id,
        String title,
        String url,
        String poster,
        String source,
        String sourceDetail,
        String year,
        Double rating,
        int sourceRank,
        String description,
        String metadata) {
    public WatchItem(String id, String title, String url, String poster, String source, String sourceDetail,
                     String year, Double rating, int sourceRank) {
        this(id, title, url, poster, source, sourceDetail, year, rating, sourceRank, "", "");
    }
}
