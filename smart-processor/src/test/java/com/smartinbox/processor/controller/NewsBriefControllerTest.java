package com.smartinbox.processor.controller;

import com.smartinbox.processor.news.NewsBriefService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NewsBriefControllerTest {
    final DashboardController feeds = mock(DashboardController.class);
    final NewsBriefService briefs = mock(NewsBriefService.class);
    final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new NewsBriefController(feeds, briefs)).build();

    @Test void acceptsOnlyExistingSourceItemsAndIgnoresClientSuppliedSummary() throws Exception {
        String url = "https://www.nbcnews.com/world/article";
        var original = new DashboardController.NewsItem("Original headline", url, "NBC News", "date", "Original facts");
        when(feeds.findUsNewsItem("NBC News", url)).thenReturn(Optional.of(original));
        when(briefs.summarize(original)).thenReturn(new NewsBriefService.Brief("中文标题", "中文概括", "NBC News", "date", "excerpt", url, true));
        mvc.perform(post("/api/dashboard/us-news/brief").contentType("application/json")
                .content("{\"source\":\"NBC News\",\"url\":\"" + url + "\",\"summary\":\"Invented facts\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("中文概括"));
        verify(briefs).summarize(original);
    }

    @Test void rejectsMissingUnknownAndExpiredItems() throws Exception {
        for (String body : new String[]{"{}", "{\"source\":\"Unknown\",\"url\":\"https://example.com\"}", "{\"source\":\"CNN\",\"url\":\"\"}"})
            mvc.perform(post("/api/dashboard/us-news/brief").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        when(feeds.findUsNewsItem("CNN", "https://example.com")).thenReturn(Optional.empty());
        mvc.perform(post("/api/dashboard/us-news/brief").contentType("application/json")
                .content("{\"source\":\"CNN\",\"url\":\"https://example.com\"}")).andExpect(status().isNotFound());
        verifyNoInteractions(briefs);
    }

    @Test void aiFailureReturnsRetryableServiceUnavailable() throws Exception {
        var item = new DashboardController.NewsItem("title", "https://cnn.com/a", "CNN", "date", "summary");
        when(feeds.findUsNewsItem(item.source(), item.url())).thenReturn(Optional.of(item));
        when(briefs.summarize(item)).thenThrow(new IllegalStateException("offline"));
        mvc.perform(post("/api/dashboard/us-news/brief").contentType("application/json")
                .content("{\"source\":\"CNN\",\"url\":\"https://cnn.com/a\"}")).andExpect(status().isServiceUnavailable());
    }
}
