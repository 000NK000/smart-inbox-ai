package com.smartinbox.processor.news;

import com.smartinbox.processor.controller.DashboardController.NewsItem;
import com.smartinbox.processor.service.AiService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewsBriefServiceTest {
    final AiService ai = mock(AiService.class);
    final NewsBriefService service = new NewsBriefService(ai);
    final AiService.NewsBriefText chinese = new AiService.NewsBriefText("中文标题", "这是根据来源整理的中文概括。");

    @Test void usesFeedExcerptAndCachesByContentNotJustUrl() {
        var item = new NewsItem("Headline", "https://www.nbcnews.com/world/article", "NBC News", "date", "The officials&rsquo; statement.");
        when(ai.summarizeNewsToChinese("Headline", "The officials’ statement.", false)).thenReturn(chinese);
        var result = service.summarize(item);
        assertEquals("excerpt", result.basis());
        assertTrue(result.officialArticle());
        assertEquals(item.url(), result.officialUrl());
        assertSame(result, service.summarize(item));
        verify(ai, times(1)).summarizeNewsToChinese("Headline", "The officials’ statement.", false);
        var updated = new NewsItem(item.title(), item.url(), item.source(), item.publishedAt(), "Updated facts.");
        when(ai.summarizeNewsToChinese("Headline", "Updated facts.", false)).thenReturn(chinese);
        service.summarize(updated);
        verify(ai).summarizeNewsToChinese("Headline", "Updated facts.", false);
    }

    @Test void distinguishesTitleOnlyIndexAndDoesNotInventAnOfficialArticleUrl() {
        var item = new NewsItem("An event happened", "https://news.google.com/rss/articles/example", "CNN", "date", "An event happened - CNN");
        when(ai.summarizeNewsToChinese(item.title(), "", true)).thenReturn(chinese);
        var result = service.summarize(item);
        assertEquals("title", result.basis());
        assertFalse(result.officialArticle());
        assertEquals("https://www.cnn.com/", result.officialUrl());
    }

    @Test void failedGenerationCanBeRetried() {
        var item = new NewsItem("News", "https://www.npr.org/a", "NPR", "date", "Facts from the feed.");
        when(ai.summarizeNewsToChinese(item.title(), item.summary(), false))
                .thenThrow(new IllegalStateException("offline")).thenReturn(chinese);
        assertThrows(IllegalStateException.class, () -> service.summarize(item));
        assertEquals(chinese.summary(), service.summarize(item).summary());
    }

    @Test void officialLinksRejectScriptsCredentialsAndDeceptiveDomains() {
        for (String url : new String[]{"javascript:alert(1)", "https://cnn.com.evil.test/a", "https://mailbox@example.com/a",
                "https://www.cnn.com:9999/a", "https://127.0.0.1/a", "https://www.npr.org/a"}) {
            assertFalse(NewsBriefService.officialLink("CNN", url).article());
        }
        assertTrue(NewsBriefService.officialLink("CNN", "https://edition.cnn.com/world/article").article());
        assertTrue(NewsBriefService.officialLink("ABC News", "https://abcnews.com/International/story").article());
    }
}
