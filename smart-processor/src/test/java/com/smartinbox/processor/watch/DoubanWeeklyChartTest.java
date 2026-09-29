package com.smartinbox.processor.watch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoubanWeeklyChartTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WatchService service = new WatchService(mapper);

    @Test void preservesWeeklyRankAndReadsWeeklyPosterFields() throws Exception {
        var root = mapper.createObjectNode();
        var items = root.putArray("subject_collection_items");
        var first = items.addObject().put("id", "100").put("title", "口碑第一名").put("rank", 1)
                .put("cover_url", "https://img1.doubanio.com/weekly.jpg").put("card_subtitle", "2026 / 剧情")
                .put("description", "这是豆瓣提供的作品简介。\n保留原始段落。");
        first.putObject("rating").put("value", 7.2);
        var second = items.addObject().put("id", "200").put("title", "高分但排第二").put("rank", 2);
        second.putObject("rating").put("value", 9.1);
        second.putObject("pic").put("normal", "https://img1.doubanio.com/second.jpg");
        var result = service.parseDouban(mapper.writeValueAsBytes(root), "一周口碑电影榜");
        assertEquals("口碑第一名", result.get(0).title());
        assertEquals(1, result.get(0).sourceRank());
        assertEquals("https://img1.doubanio.com/weekly.jpg", result.get(0).poster());
        assertEquals("https://img1.doubanio.com/second.jpg", result.get(1).poster());
        assertEquals("https://movie.douban.com/subject/100/", result.get(0).url());
        assertEquals("一周口碑电影榜", result.get(0).sourceDetail());
        assertEquals("2026", result.get(0).year());
        assertEquals("2026 / 剧情", result.get(0).metadata());
        assertEquals("这是豆瓣提供的作品简介。\n保留原始段落。", result.get(0).description());
        assertEquals("", result.get(1).description());
    }

    @Test void capsAtTenWithoutInventingMissingEntries() throws Exception {
        var root = mapper.createObjectNode();
        var items = root.putArray("subject_collection_items");
        for (int index = 0; index < 6; index++) items.addObject().put("title", "综艺 " + index).put("id", index);
        assertEquals(6, service.parseDouban(mapper.writeValueAsBytes(root), "国内综艺").size());
        for (int index = 6; index < 13; index++) items.addObject().put("title", "综艺 " + index).put("id", index);
        assertEquals(10, service.parseDouban(mapper.writeValueAsBytes(root), "国内综艺").size());
        assertTrue(service.parseDouban("{}".getBytes(), "国内综艺").isEmpty());
    }

    @Test void hasFiveSeparateWeeklyFeedsWithCorrectWatchlistKinds() {
        assertEquals(5, DoubanWeeklyChart.values().length);
        assertEquals(5, java.util.Arrays.stream(DoubanWeeklyChart.values()).map(DoubanWeeklyChart::apiUrl).distinct().count());
        assertEquals("MOVIE", DoubanWeeklyChart.MOVIE.kind);
        assertEquals("TV", DoubanWeeklyChart.CHINESE_TV.kind);
        assertEquals("TV", DoubanWeeklyChart.GLOBAL_TV.kind);
        assertEquals("VARIETY", DoubanWeeklyChart.CHINESE_SHOW.kind);
        assertEquals("VARIETY", DoubanWeeklyChart.GLOBAL_SHOW.kind);
        assertTrue(DoubanWeeklyChart.MOVIE.apiUrl().contains("movie_weekly_best/items"));
    }
}
