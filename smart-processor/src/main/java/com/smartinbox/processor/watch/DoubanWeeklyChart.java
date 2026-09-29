package com.smartinbox.processor.watch;

public enum DoubanWeeklyChart {
    MOVIE("movie_weekly_best", "电影", "一周口碑电影榜", "MOVIE"),
    CHINESE_TV("tv_chinese_best_weekly", "华语剧集", "一周华语剧集口碑榜", "TV"),
    GLOBAL_TV("tv_global_best_weekly", "全球剧集", "一周全球剧集口碑榜", "TV"),
    CHINESE_SHOW("show_chinese_best_weekly", "国内综艺", "一周国内综艺口碑榜", "VARIETY"),
    GLOBAL_SHOW("show_global_best_weekly", "国外综艺", "一周国外综艺口碑榜", "VARIETY");

    public final String id;
    public final String label;
    public final String title;
    public final String kind;

    DoubanWeeklyChart(String id, String label, String title, String kind) {
        this.id = id; this.label = label; this.title = title; this.kind = kind;
    }

    public String pageUrl() { return "https://m.douban.com/subject_collection/" + id; }
    public String apiUrl() {
        return "https://m.douban.com/rexxar/api/v2/subject_collection/" + id
                + "/items?start=0&count=10&items_only=1&for_mobile=1";
    }
}
