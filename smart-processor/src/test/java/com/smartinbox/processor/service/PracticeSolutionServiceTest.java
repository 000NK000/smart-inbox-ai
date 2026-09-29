package com.smartinbox.processor.service;

import com.smartinbox.processor.controller.PracticeSolutionController;
import com.smartinbox.processor.entity.PracticeSolution;
import com.smartinbox.processor.repository.PracticeSolutionImageRepository;
import com.smartinbox.processor.repository.PracticeSolutionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"}, showSql=false)
@ContextConfiguration(classes = PracticeSolutionServiceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PracticeSolutionServiceTest {
    @Configuration @EntityScan(basePackageClasses = PracticeSolution.class)
    @EnableJpaRepositories(basePackageClasses = PracticeSolutionRepository.class)
    @Import({PracticeSolutionService.class, PracticeSolutionController.class})
    static class Config {}

    @Autowired PracticeSolutionService service;
    @Autowired PracticeSolutionController controller;
    @Autowired PracticeSolutionRepository solutions;
    @Autowired PracticeSolutionImageRepository images;
    @BeforeEach void clean() { images.deleteAll(); solutions.deleteAll(); }

    @Test void independentNotesAndScreenshotsPersistForAnyProblem() throws Exception {
        var screenshot = new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3};
        var encoded = Base64.getEncoder().encodeToString(screenshot);
        var first = service.save(525, new PracticeSolutionService.Input("思路：前缀和 + 哈希表", List.of(
                new PracticeSolutionService.ImageInput(null,"image/png",encoded,"手写推导")), null));
        assertEquals("思路：前缀和 + 哈希表", first.content());
        assertEquals(List.of(525), service.numbers());
        assertArrayEquals(screenshot, service.image(first.images().get(0).id()).data());
        var reread = service.get(525);
        assertEquals("手写推导", reread.images().get(0).caption());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/practice/solution-images/" + reread.images().get(0).id()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));

        var edited = service.save(525, new PracticeSolutionService.Input("补充：记录首次出现的下标", List.of(
                new PracticeSolutionService.ImageInput(reread.images().get(0).id(),null,null,"更清晰的推导")), reread.version()));
        assertEquals("更清晰的推导", edited.images().get(0).caption());
        assertEquals(1, images.count());
        assertThrows(ResponseStatusException.class, () -> service.save(525,
                new PracticeSolutionService.Input("旧页面覆盖", List.of(), reread.version())));
        var cleared = service.save(525, new PracticeSolutionService.Input("", List.of(), edited.version()));
        assertNull(cleared.version());
        assertTrue(service.numbers().isEmpty());
        assertEquals(0, images.count());
    }

    @Test void rejectsFakedOrCrossProblemImages() {
        var faked = Base64.getEncoder().encodeToString("<svg><script/></svg>".getBytes());
        assertThrows(ResponseStatusException.class, () -> service.save(236,
                new PracticeSolutionService.Input("内容", List.of(new PracticeSolutionService.ImageInput(null,"image/png",faked,"")),null)));
        var image = Base64.getEncoder().encodeToString(new byte[]{(byte)137,80,78,71,13,10,26,10,1});
        var first = service.save(236,new PracticeSolutionService.Input("内容", List.of(new PracticeSolutionService.ImageInput(null,"image/png",image,"")),null));
        assertThrows(ResponseStatusException.class, () -> service.save(525,
                new PracticeSolutionService.Input("另一题", List.of(new PracticeSolutionService.ImageInput(first.images().get(0).id(),null,null,"")),null)));
        assertEquals(1, images.count());
        assertFalse(solutions.existsById(525));
    }
}
