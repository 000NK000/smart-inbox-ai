package com.smartinbox.processor.mail;

import com.smartinbox.processor.controller.MailSummaryController;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false", "spring.jpa.show-sql=false"}, showSql=false)
@ContextConfiguration(classes = MailQueryIntegrationTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MailQueryIntegrationTest {
    static final List<String> statements = new CopyOnWriteArrayList<>();
    @Configuration @EntityScan(basePackageClasses = MailSummary.class)
    @EnableJpaRepositories(basePackageClasses = MailSummaryRepository.class)
    @Import({MailQueryService.class, MailSummaryController.class})
    static class Config {
        @Bean HibernatePropertiesCustomizer inspector() { return props -> props.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> { statements.add(sql); return sql; }); }
        @Bean ReadEvents readEvents() { return new ReadEvents(); }
    }
    static class ReadEvents {
        final List<MailReadStateChanged> committed = new ArrayList<>();
        @org.springframework.transaction.event.TransactionalEventListener
        public void changed(MailReadStateChanged event) { committed.add(event); }
    }
    @Autowired ReadEvents readEvents;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired MailQueryService query;
    @Autowired MailSummaryController controller;
    @Autowired MailSummaryRepository mails;
    @Autowired EntityManager em;
    @BeforeEach void clear() { mails.deleteAll(); statements.clear(); readEvents.committed.clear(); }
    MailSummary mail(String title, int hoursAgo) {
        var m = new MailSummary(); m.setExternalId(UUID.randomUUID().toString()); m.setSubject(title); m.setOriginalSubject(title);
        m.setSender("Synthetic sender"); m.setSummary("Synthetic summary"); m.setSource("GMAIL"); m.setCategory("Work"); m.setUrgency(2);
        m.setContent("PrivateBodyForProjectionCheck"); m.setHtmlContent("<b>PrivateHtmlForProjectionCheck</b>");
        m.setCreatedTime(LocalDateTime.now().minusHours(hoursAgo)); return mails.saveAndFlush(m);
    }
    MailQueryService.Filter filter(String view, String search) { return new MailQueryService.Filter("EMAIL", "GMAIL", null, null, view, false, search, 120, null); }
    @Test void listIsProjectionAndSearchTreatsPercentUnderscoreAsLiterals() {
        var percent = mail("Literal 100% complete", 1); mail("100 percent complete", 1);
        var underscore = mail("Literal invoice_A", 1); mail("Literal invoiceXA", 1);
        assertEquals(List.of(percent.getId()), query.list(filter("INBOX", "%"), 0, 20).getContent().stream().map(MailQueryService.Item::id).toList());
        assertEquals(List.of(underscore.getId()), query.list(filter("INBOX", "_"), 0, 20).getContent().stream().map(MailQueryService.Item::id).toList());
        statements.clear(); var page = query.list(filter("INBOX", null), 0, 20); assertEquals(4, page.getTotalElements());
        var selects = statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("select")).toList();
        assertFalse(selects.isEmpty());
        for (String sql : selects) {
            String columns = sql.substring(0, sql.toLowerCase(Locale.ROOT).indexOf(" from ")).toLowerCase(Locale.ROOT);
            assertFalse(columns.contains(".content"), sql); assertFalse(columns.contains(".html_content"), sql);
        }
        assertEquals(4, query.list(filter("INBOX", "PrivateBodyForProjectionCheck"), 0, 20).getTotalElements());
        var longBody = mail("Long body", 1);
        longBody.setContent("x".repeat(20000) + " NeedleAtTheVeryEnd"); mails.saveAndFlush(longBody);
        assertEquals(List.of(longBody.getId()), query.list(filter("INBOX", "needleattheveryend"), 0, 20).getContent().stream().map(MailQueryService.Item::id).toList());
    }
    @Test void snoozeReturnsAcross120HourWindowAndAckCancelReadRemainLocal() {
        mail("Recent", 119); var old = mail("Expired regular", 121);
        var snoozed = mail("Old deliberately snoozed", 240); long due = System.currentTimeMillis()-10000;
        snoozed.setSnoozedUntil(due); mails.saveAndFlush(snoozed);
        var future = mail("Future snoozed", 240); future.setSnoozedUntil(System.currentTimeMillis()+3600000); mails.saveAndFlush(future);
        assertEquals(2, query.list(filter("INBOX", null), 0, 20).getTotalElements());
        assertEquals(1, query.list(filter("SNOOZED", null), 0, 20).getTotalElements());
        assertEquals(1, query.reminders().size());
        controller.acknowledge(snoozed.getId(), new MailSummaryController.SnoozeRequest(due-1));
        assertEquals(1, query.reminders().size(), "A stale reminder ACK must not match a new schedule");
        controller.acknowledge(snoozed.getId(), new MailSummaryController.SnoozeRequest(due)); assertTrue(query.reminders().isEmpty());
        assertEquals(2, query.list(filter("INBOX", null), 0, 20).getTotalElements());
        controller.snooze(snoozed.getId(), new MailSummaryController.SnoozeRequest(null));
        assertEquals(1, query.list(filter("INBOX", null), 0, 20).getTotalElements());
        controller.updateStarState(old.getId(), new MailSummaryController.MailStateRequest(true));
        assertEquals(1, query.list(filter("STARRED", null), 0, 20).getTotalElements());
        controller.updateReadState(future.getId(), new MailSummaryController.MailStateRequest(true));
        assertEquals(0, query.list(filter("SNOOZED", null), 0, 20).getTotalElements());
        assertNull(mails.findById(future.getId()).orElseThrow().getSnoozedUntil());
    }
    @Test void revisionsChangeForLocalStateAndH2UsesSourceTimeIndex() {
        var m = mail("Revision", 1); String before = query.revision(filter("INBOX", null)).version();
        controller.updateStarState(m.getId(), new MailSummaryController.MailStateRequest(true));
        assertNotEquals(before, query.revision(filter("INBOX", null)).version());
        var revision = mails.taskPlanRevision(MailWindow.cutoff(), MailWindow.SOURCES);
        assertEquals(1, revision.getMailCount()); assertNotNull(revision.getLastChanged());
        String plan = em.createNativeQuery("EXPLAIN SELECT id FROM mail_summary WHERE source='GMAIL' AND created_time>=CURRENT_TIMESTAMP - INTERVAL '5' DAY ORDER BY created_time DESC").getSingleResult().toString();
        assertTrue(plan.toUpperCase(Locale.ROOT).contains("IX_MAIL_SOURCE_RECEIVED"), plan);
        System.out.println("Synthetic H2 EXPLAIN: " + plan);
        assertEquals(1, query.syncIndex("GMAIL").size()); assertTrue(query.syncIndex("GMAIL").get(0).bodyAvailable());
    }

    @Test void plannerExcludesOnlyLocalReadMailAndKeepsIngestionIndex() {
        var unread = mail("Recent unread", 1);
        var read = mail("Recent locally read", 2);
        mail("Expired", 121);
        controller.updateReadState(read.getId(), new MailSummaryController.MailStateRequest(true));
        assertEquals(List.of(unread.getId()), mails.findTaskPlanMails(MailWindow.cutoff(), MailWindow.SOURCES).stream().map(MailSummary::getId).toList());
        assertEquals(1, mails.taskPlanRevision(MailWindow.cutoff(), MailWindow.SOURCES).getMailCount());
        assertTrue(query.syncIndex("GMAIL").stream().anyMatch(row -> row.externalId().equals(read.getExternalId())));
        controller.updateReadState(read.getId(), new MailSummaryController.MailStateRequest(false));
        assertEquals(2, mails.findTaskPlanMails(MailWindow.cutoff(), MailWindow.SOURCES).size());
    }

    @Test void readNotificationOnlyOccursAfterSuccessfulCommit() {
        var m = mail("Transaction boundary", 1);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            controller.updateReadState(m.getId(), new MailSummaryController.MailStateRequest(true));
            assertTrue(readEvents.committed.isEmpty());
            status.setRollbackOnly();
        });
        assertTrue(readEvents.committed.isEmpty());
        assertFalse(mails.findById(m.getId()).orElseThrow().isInboxRead());
        controller.updateReadState(m.getId(), new MailSummaryController.MailStateRequest(true));
        assertEquals(List.of(new MailReadStateChanged(m.getId(), true)), readEvents.committed);
    }
}
