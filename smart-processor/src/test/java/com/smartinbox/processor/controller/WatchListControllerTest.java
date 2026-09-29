package com.smartinbox.processor.controller;

import com.fasterxml.jackson.databind.*;
import com.smartinbox.processor.entity.WatchListEntry;
import com.smartinbox.processor.repository.WatchListRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"})
@ContextConfiguration(classes = WatchListControllerTest.Config.class)
class WatchListControllerTest {
    @Configuration
    @EntityScan(basePackageClasses = WatchListEntry.class)
    @EnableJpaRepositories(basePackageClasses = WatchListRepository.class)
    @Import(WatchListController.class)
    static class Config {}

    @Autowired WatchListController controller;
    @Autowired WatchListRepository repository;
    MockMvc mvc;
    final ObjectMapper mapper = new ObjectMapper();
    final String api = "/api/dashboard/watchlist";
    @BeforeEach void setup() { mvc = MockMvcBuilders.standaloneSetup(controller).build(); }
    JsonNode create(String title, String kind) throws Exception {
        String json = mapper.createObjectNode().put("title", title).put("kind", kind).toString();
        return mapper.readTree(mvc.perform(post(api).contentType("application/json").content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }
    @Test void createsEditsListsAndDeletesWithProgressAndNotes() throws Exception {
        var entry = create("  我的测试剧  ", "TV");
        String id = entry.path("id").asText();
        var update = mapper.createObjectNode().put("title", "我的测试剧 第二季").put("kind", "TV")
                .put("status", "WATCHING").put("currentEpisode", 3).put("totalEpisodes", 10)
                .put("notes", "周末看").put("version", entry.path("version").asLong());
        var saved = mapper.readTree(mvc.perform(put(api + "/" + id).contentType("application/json").content(update.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentEpisode").value(3))
                .andExpect(jsonPath("$.notes").value("周末看")).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get(api)).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("我的测试剧 第二季"));
        mvc.perform(delete(api + "/" + id).param("version", saved.path("version").asText())).andExpect(status().isNoContent());
        assertFalse(repository.existsById(id));
    }
    @Test void duplicatesAreRejectedButMovieAndTvAreSeparate() throws Exception {
        create("Example", "TV");
        mvc.perform(post(api).contentType("application/json").content("{\"title\":\" example \",\"kind\":\"TV\"}"))
                .andExpect(status().isConflict());
        create("Example", "MOVIE");
        assertEquals(2, repository.count());
    }
    @Test void invalidInputCannotPersist() throws Exception {
        for (String json : new String[]{"{\"title\":\" \"}", "{\"title\":\"test\",\"kind\":\"INVALID\"}",
                "{\"title\":\"test\",\"currentEpisode\":8,\"totalEpisodes\":3}",
                "{\"title\":\"test\",\"url\":\"javascript:alert(1)\"}"}) {
            mvc.perform(post(api).contentType("application/json").content(json)).andExpect(status().isBadRequest());
        }
        assertEquals(0, repository.count());
    }
    @Test void varietyShowsKeepTheirKindAndEpisodeProgress() throws Exception {
        var entry = create("我的综艺", "VARIETY");
        String body = "{\"title\":\"我的综艺\",\"kind\":\"VARIETY\",\"status\":\"WATCHING\",\"currentEpisode\":2,\"totalEpisodes\":8,\"version\":" + entry.path("version") + "}";
        mvc.perform(put(api + "/" + entry.path("id").asText()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("VARIETY"))
                .andExpect(jsonPath("$.currentEpisode").value(2));
    }
    @Test void staleBrowserCannotOverwriteOrDeleteNewerChanges() throws Exception {
        var entry = create("Progress", "TV");
        String id = entry.path("id").asText();
        String update = "{\"title\":\"Updated\",\"kind\":\"TV\",\"version\":" + entry.path("version") + "}";
        mvc.perform(put(api + "/" + id).contentType("application/json").content(update)).andExpect(status().isOk());
        mvc.perform(put(api + "/" + id).contentType("application/json").content(update)).andExpect(status().isConflict());
        mvc.perform(delete(api + "/" + id).param("version", entry.path("version").asText())).andExpect(status().isConflict());
        assertEquals("Updated", repository.findById(id).orElseThrow().getTitle());
    }
}
