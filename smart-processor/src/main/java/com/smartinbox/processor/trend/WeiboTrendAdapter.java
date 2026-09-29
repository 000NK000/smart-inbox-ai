package com.smartinbox.processor.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WeiboTrendAdapter implements TrendAdapter {

    private static final String ENDPOINT = "https://weibo.com/ajax/side/hotSearch";
    private static final String RSS_FALLBACK = "https://rsshub.app/weibo/search/hot";
    private final ObjectMapper objectMapper;
    private final TrendHttpClient httpClient;

    public WeiboTrendAdapter(ObjectMapper objectMapper, TrendHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return "weibo";
    }

    @Override
    public String name() {
        return "微博";
    }

    @Override
    public List<TrendItem> fetch() throws Exception {
        try {
            return fetchDirect();
        } catch (Exception directError) {
            return fetchRssFallback();
        }
    }

    private List<TrendItem> fetchDirect() throws Exception {
        JsonNode realtime = objectMapper.readTree(httpClient.get(ENDPOINT, "https://weibo.com/"))
                .path("data").path("realtime");
        if (!realtime.isArray()) {
            throw new IllegalStateException("Weibo response did not contain a realtime list");
        }

        List<TrendItem> result = new ArrayList<>();
        for (JsonNode item : realtime) {
            String word = item.path("word").asText("").trim();
            if (word.isBlank() || item.path("is_ad").asInt(0) == 1) {
                continue;
            }
            String query = item.path("word_scheme").asText("").trim();
            if (query.isBlank()) {
                query = "#" + word + "#";
            }
            long heat = item.path("raw_hot").asLong(item.path("num").asLong(0));
            String badge = item.path("label_name").asText("").trim();
            result.add(new TrendItem(
                    word,
                    "https://s.weibo.com/weibo?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
                    heat,
                    badge));
            if (result.size() == 10) {
                break;
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException(String.format(Locale.ROOT, "%s returned no trends", name()));
        }
        return result;
    }

    private List<TrendItem> fetchRssFallback() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);

        NodeList nodes = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(httpClient.get(RSS_FALLBACK, "https://weibo.com/")))
                .getElementsByTagName("item");
        List<TrendItem> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength() && result.size() < 10; index++) {
            Element item = (Element) nodes.item(index);
            String title = childText(item, "title");
            String link = childText(item, "link");
            if (!title.isBlank() && !link.isBlank()) {
                result.add(new TrendItem(title, link, 0, ""));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Weibo fallback returned no trends");
        }
        return result;
    }

    private String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 || nodes.item(0).getTextContent() == null
                ? ""
                : nodes.item(0).getTextContent().trim();
    }
}
