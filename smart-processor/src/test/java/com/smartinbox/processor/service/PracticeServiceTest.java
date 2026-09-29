package com.smartinbox.processor.service;

import com.smartinbox.processor.controller.PracticeController;
import com.smartinbox.processor.entity.PracticeProgress;
import com.smartinbox.processor.repository.PracticeProgressRepository;
import com.smartinbox.processor.repository.PracticeGroupRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"}, showSql=false)
@ContextConfiguration(classes = PracticeServiceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PracticeServiceTest {
    @Configuration @EntityScan(basePackageClasses = PracticeProgress.class)
    @EnableJpaRepositories(basePackageClasses = PracticeProgressRepository.class)
    @Import({PracticeService.class, PracticeController.class})
    static class Config {}

    @Autowired PracticeService service;
    @Autowired PracticeController controller;
    @Autowired PracticeProgressRepository repository;
    @Autowired PracticeGroupRepository groups;
    @BeforeEach void clean() { repository.deleteAll(); groups.deleteAll(); }

    PracticeService.Attempt attempt(String result, Long version, String zone) {
        return new PracticeService.Attempt(525,"连续数组","前缀和",result,35,"METHOD","没有想到前缀和",zone,version);
    }
    @Test void recordsTwoLevelsWithoutForcingAReviewDeadline() throws Exception {
        assertTrue(service.list().isEmpty());
        var unfamiliar=service.record(attempt("YELLOW",null,"America/Toronto"));
        assertEquals(1,unfamiliar.getAttempts());assertEquals(35,unfamiliar.getTotalMinutes());assertEquals("YELLOW",unfamiliar.getStatus());
        assertNull(unfamiliar.getNextReviewAt());
        var green=service.record(attempt("GREEN",unfamiliar.getVersion(),"America/Toronto"));
        assertEquals(2,green.getAttempts());assertEquals(1,green.getYellowCount());assertEquals(1,green.getGreenCount());
        assertEquals(70,green.getTotalMinutes());
        assertNull(green.getNextReviewAt());
        assertEquals(2,service.list().get(0).getAttempts());
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/practice")).andExpect(status().isOk()).andExpect(jsonPath("$[0].number").value(525));
    }
    @Test void staleVersionsAndInvalidAttemptsNeverAlterSavedProgress() {
        var first=service.record(attempt("YELLOW",null,"America/Toronto"));
        assertThrows(ResponseStatusException.class,()->service.record(attempt("RED",first.getVersion(),"America/Toronto")));
        assertThrows(ResponseStatusException.class,()->service.record(attempt("GREEN",null,"America/Toronto")));
        assertThrows(ResponseStatusException.class,()->service.record(new PracticeService.Attempt(525,"连续数组","前缀和","GREEN",999,"NONE","", "America/Toronto",first.getVersion())));
        assertEquals(1,repository.findById(525).orElseThrow().getAttempts());
        assertThrows(ResponseStatusException.class,()->service.delete(525,null));
        service.delete(525,first.getVersion());
        assertFalse(repository.existsById(525));
    }
    @Test void sharedGroupNotesAndMovingDoNotChangePracticeHistory() throws Exception {
        var first = service.record(attempt("YELLOW", null, "America/Toronto"));
        var group = service.saveGroup(new PracticeService.GroupInput("动态规划", "先定义状态，再找转移。", null));
        assertEquals("先定义状态，再找转移。", service.groups().get(0).getNote());
        var updated = service.saveGroup(new PracticeService.GroupInput("动态规划", "考虑初始化和边界。", group.getVersion()));
        assertNotEquals(group.getVersion(), updated.getVersion());
        assertThrows(ResponseStatusException.class, () -> service.saveGroup(new PracticeService.GroupInput("动态规划", "旧页面覆盖", group.getVersion())));
        var moved = service.move(525, new PracticeService.MoveInput("动态规划", first.getVersion()));
        assertEquals("动态规划", moved.getTopic());
        assertEquals(1, moved.getAttempts());
        assertEquals(35, moved.getTotalMinutes());
        assertEquals("YELLOW", moved.getStatus());
        assertEquals(first.getLastPracticedAt(), moved.getLastPracticedAt());
        assertThrows(ResponseStatusException.class, () -> service.move(525, new PracticeService.MoveInput("其他", first.getVersion())));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/practice/groups")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("动态规划"));
    }
}
