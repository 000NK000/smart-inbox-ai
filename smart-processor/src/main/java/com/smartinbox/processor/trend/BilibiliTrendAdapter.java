package com.smartinbox.processor.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BilibiliTrendAdapter implements TrendAdapter {

    private static final String ENDPOINT = "https://s.search.bilibili.com/main/hotword";
    private final ObjectMapper objectMapper;
    private final TrendHttpClient httpClient;

    public BilibiliTrendAdapter(ObjectMapper objectMapper, TrendHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return "bilibili";
    }

    @Override
    public String name() {
        return "B站";
    }

    @Override
    public List<TrendItem> fetch() throws Exception {
        JsonNode root = objectMapper.readTree(httpClient.get(ENDPOINT, "https://www.bilibili.com/"));
        if (root.path("code").asInt(-1) != 0 || !root.path("list").isArray()) {
            throw new IllegalStateException("Bilibili response did not contain a hotword list");
        }

        List<TrendItem> result = new ArrayList<>();
        for (JsonNode item : root.path("list")) {
            String keyword = item.path("show_name").asText(item.path("keyword").asText("")).trim();
            if (keyword.isBlank()) {
                continue;
            }
            String searchKeyword = item.path("keyword").asText(keyword).trim();
            result.add(new TrendItem(
                    keyword,
                    "https://search.bilibili.com/all?keyword=" + URLEncoder.encode(searchKeyword, StandardCharsets.UTF_8)
                            + "&from_source=webtop_search",
                    item.path("heat_score").asLong(0),
                    badge(item.path("word_type").asInt(0))));
            if (result.size() == 10) {
                break;
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Bilibili returned no trends");
        }
        return result;
    }

    private String badge(int wordType) {
        return switch (wordType) {
            case 4 -> "新";
            case 5 -> "热";
            case 9 -> "梗";
            case 11 -> "话题";
            default -> "";
        };
    }
}
