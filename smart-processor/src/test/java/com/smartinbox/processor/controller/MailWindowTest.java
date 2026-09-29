package com.smartinbox.processor.controller;

import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.repository.*;
import com.smartinbox.processor.mail.MailWindow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties={"spring.cloud.bootstrap.enabled=false","spring.cloud.nacos.discovery.enabled=false","spring.cloud.nacos.config.enabled=false"})
@ContextConfiguration(classes=MailWindowTest.Config.class)
class MailWindowTest {
    @Configuration @EntityScan(basePackageClasses=MailSummary.class)
    @EnableJpaRepositories(basePackageClasses=MailSummaryRepository.class) @Import({MailSummaryController.class,com.smartinbox.processor.mail.MailQueryService.class})
    static class Config {}
    @Autowired MailSummaryRepository mails;
    @Autowired MailSummaryController controller;
    MailSummary add(int hours, String source, boolean read, boolean starred) {
        var mail=new MailSummary(); mail.setSubject("Window test"); mail.setContent("Training required.");
        mail.setCreatedTime(LocalDateTime.now().minusHours(hours)); mail.setSource(source);
        mail.setInboxRead(read); mail.setStarred(starred); mail.setCategory("Ad"); return mails.save(mail);
    }
    @Test void plannerIncludesAllChannelsAndLocalReadBeyondFirstPageWithin120Hours() {
        for(int i=0;i<25;i++) add(110, i%2==0 ? "GMAIL" : "QQMAIL", true, false);
        add(119, "OUTLOOK", false, false); add(121, "OUTLOOK", false, true); add(2, "YOUTUBE", false, false);
        var recent=mails.findAllByCreatedTimeGreaterThanEqualAndSourceInOrderByCreatedTimeDesc(MailWindow.cutoff(),MailWindow.SOURCES);
        assertEquals(26, recent.size());
        var inbox=controller.getSummaries(0,20,"EMAIL",null,null,null,"INBOX",false);
        assertEquals(1, inbox.getTotalElements());
        assertEquals("OUTLOOK", inbox.getContent().get(0).source());
        assertEquals(1, controller.getSummaries(0,20,"EMAIL",null,null,null,"STARRED",false).getTotalElements());
        assertEquals(26, controller.getSummaries(0,50,"EMAIL",null,null,null,null,false).getTotalElements());
    }
    @Test void listsAndRevisionsOnlyIncludeEmailEvenForMissingOrRetiredTabs() {
        for (String source : MailWindow.SOURCES) add(90, source, false, false);
        add(2, "YOUTUBE", false, false);
        add(2, "YouTube", false, false);
        add(2, "INSTAGRAM", false, false);
        add(2, null, false, false);

        for (String tab : Arrays.asList(null, "EMAIL", "ENTERTAINMENT")) {
            var result = controller.getSummaries(0,20,tab,null,null,null,"INBOX",false);
            assertEquals(MailWindow.SOURCES.size(), result.getTotalElements());
            assertTrue(result.getContent().stream().allMatch(item -> MailWindow.SOURCES.contains(item.source())));
            assertEquals(result.getTotalElements(), controller.revision(tab,null,null,null,"INBOX",false,null,null,null).totalElements());
        }
    }
    @Test void supportedSourceAliasesStillFilterTheirEmailChannel() {
        for (String source : MailWindow.SOURCES) add(2, source, false, false);
        add(2, "YOUTUBE", false, false);
        var families = List.of(List.of("GMAIL", "Google Mail"), List.of("QQMAIL", "QQ Mail"),
                List.of("OUTLOOK", "Outlook", "Microsoft 365"));
        for (var family : families) {
            for (String alias : family) {
                String source = " " + alias.toLowerCase(Locale.ROOT) + " ";
                var result = controller.getSummaries(0,20,null,source,null,null,null,false);
                assertEquals(family.size(), result.getTotalElements());
                assertTrue(result.getContent().stream().allMatch(item -> family.contains(item.source())));
                assertEquals(family.size(), ((List<?>) controller.syncIndex(source).get("items")).size());
            }
        }
    }
    @Test void retiredAndUnknownSourcesCannotBypassEmailFiltering() {
        add(2, "YOUTUBE", false, false);
        for (String source : List.of("YOUTUBE", "YouTube", "INSTAGRAM", "UNKNOWN")) {
            var listError = assertThrows(ResponseStatusException.class,
                    () -> controller.getSummaries(0,20,null,source,null,null,null,false));
            assertEquals(400, listError.getStatusCode().value());
            var revisionError = assertThrows(ResponseStatusException.class,
                    () -> controller.revision(null,source,null,null,null,false,null,null,null));
            assertEquals(400, revisionError.getStatusCode().value());
            var syncError = assertThrows(ResponseStatusException.class, () -> controller.syncIndex(source));
            assertEquals(400, syncError.getStatusCode().value());
        }
    }
    @Test void entertainmentCategoryRemainsAvailableForActualEmails() {
        add(2, "GMAIL", false, false).setCategory("Entertainment");
        add(2, "YouTube", false, false).setCategory("Entertainment");
        var result = controller.getSummaries(0,20,null,null,"Entertainment",null,null,false);
        assertEquals(1, result.getTotalElements());
        assertEquals("GMAIL", result.getContent().get(0).source());
    }
    @Test void remindersExcludeRetiredSources() {
        long due = System.currentTimeMillis() - 1000;
        var email = add(2, "QQMAIL", false, false);
        email.setSnoozedUntil(due);
        add(2, "YOUTUBE", false, false).setSnoozedUntil(due);
        var result = controller.reminders();
        assertEquals(1, result.size());
        assertEquals(email.getId(), result.get(0).get("id"));
    }
}
