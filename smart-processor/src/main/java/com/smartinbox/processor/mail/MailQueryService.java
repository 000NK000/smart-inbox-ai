package com.smartinbox.processor.mail;

import com.smartinbox.processor.entity.MailSummary;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

/** List and sync projections deliberately exclude both CLOB columns. */
@Service
@Transactional(readOnly = true)
public class MailQueryService {
    @PersistenceContext private EntityManager em;
    public record Item(Long id, String externalId, String subject, String sender, String category,
                       String summary, Integer urgency, String action, String status, LocalDateTime createdTime,
                       String source, Boolean inboxRead, Boolean starred, Long snoozedUntil) {}
    public record Filter(String tab, String source, String category, Integer urgency, String view,
                         boolean excludeAd, String q, Integer hours, Boolean starred) {}
    public record Revision(long totalElements, String version) {}
    private List<String> sources(String source) {
        return switch (Objects.toString(source, "").trim().toUpperCase(Locale.ROOT)) {
            case "GMAIL", "GOOGLE MAIL" -> List.of("GMAIL", "Google Mail");
            case "QQMAIL", "QQ MAIL" -> List.of("QQMAIL", "QQ Mail");
            case "OUTLOOK", "MICROSOFT 365" -> List.of("OUTLOOK", "Outlook", "Microsoft 365");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮件来源无效");
        };
    }
    private Specification<MailSummary> filter(Filter f) {
        return (r,q,c) -> {
            List<Predicate> p = new ArrayList<>();
            String view = Objects.toString(f.view(), "").toUpperCase(Locale.ROOT);
            long now = System.currentTimeMillis();
            int hours = f.hours() == null ? MailWindow.HOURS : f.hours();
            if (!view.equals("STARRED") && !view.equals("SNOOZED")) {
                var recent = c.greaterThanOrEqualTo(r.get("createdTime"), LocalDateTime.now().minusHours(hours));
                // A deliberately snoozed message must return even when its original receipt is older than 5 days.
                p.add(view.equals("INBOX") ? c.or(recent, c.isNotNull(r.get("snoozedUntil"))) : recent);
            }
            p.add(r.get("source").in(MailWindow.SOURCES));
            if (f.source()!=null && !f.source().isBlank()) p.add(r.get("source").in(sources(f.source())));
            if (f.category()!=null && !f.category().isBlank() && !Set.of("EmailOnly","All","全部").contains(f.category())) p.add(c.equal(r.get("category"),f.category()));
            if (f.urgency()!=null) p.add(c.equal(r.get("urgency"),f.urgency()));
            if (view.equals("INBOX") || view.equals("SNOOZED")) p.add(c.or(c.isFalse(r.get("inboxRead")), c.isNull(r.get("inboxRead"))));
            if (view.equals("INBOX")) p.add(c.or(c.isNull(r.get("snoozedUntil")), c.le(r.get("snoozedUntil"),now)));
            if (view.equals("SNOOZED")) p.add(c.gt(r.get("snoozedUntil"),now));
            if (view.equals("STARRED") || Boolean.TRUE.equals(f.starred())) p.add(c.isTrue(r.get("starred")));
            if (f.excludeAd()) p.add(c.or(c.isNull(r.get("category")),c.not(r.get("category").in("Ad","[Ad]","广告","[广告]","ad"))));
            if (f.q()!=null && !f.q().isBlank()) {
                String term = "%" + f.q().trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%";
                p.add(c.or(c.like(c.lower(r.get("subject")),term,'\\'),c.like(c.lower(r.get("originalSubject")),term,'\\'),
                    c.like(c.lower(r.get("sender")),term,'\\'),c.like(c.lower(r.get("content").as(String.class)),term,'\\')));
            }
            return c.and(p.toArray(Predicate[]::new));
        };
    }
    public Page<Item> list(Filter f,int page,int size) {
        var c=em.getCriteriaBuilder(); var q=c.createQuery(Item.class); var r=q.from(MailSummary.class);
        q.select(c.construct(Item.class,r.get("id"),r.get("externalId"),r.get("subject"),r.get("sender"),r.get("category"),
            r.get("summary"),r.get("urgency"),r.get("action"),r.get("status"),r.get("createdTime"),r.get("source"),r.get("inboxRead"),r.get("starred"),r.get("snoozedUntil")));
        q.where(filter(f).toPredicate(r,q,c));
        q.orderBy(c.desc(r.get("urgency")),c.desc(r.get("createdTime")),c.desc(r.get("id")));
        List<Item> items=em.createQuery(q).setFirstResult(Math.multiplyExact(page,size)).setMaxResults(size).getResultList();
        return new PageImpl<>(items,PageRequest.of(page,size),revision(f).totalElements());
    }
    public Revision revision(Filter f) {
        var c=em.getCriteriaBuilder(); var q=c.createTupleQuery(); var r=q.from(MailSummary.class);
        q.multiselect(c.count(r),c.greatest(r.<LocalDateTime>get("updatedTime")),c.max(r.<Long>get("id")));
        q.where(filter(f).toPredicate(r,q,c)); var row=em.createQuery(q).getSingleResult();
        return new Revision(row.get(0,Long.class),row.get(0)+":"+row.get(1)+":"+row.get(2));
    }
    public record SyncItem(String externalId, Boolean bodyAvailable) {}
    public List<SyncItem> syncIndex(String source) {
        return em.createQuery("select new com.smartinbox.processor.mail.MailQueryService$SyncItem(m.externalId, case when m.bodySynced=true or length(m.htmlContent)>0 then true else false end) from MailSummary m where m.source in :sources and m.createdTime>=:cutoff",SyncItem.class)
            .setParameter("sources",sources(source)).setParameter("cutoff",MailWindow.cutoff()).getResultList();
    }
    public List<Map<String,Object>> reminders() {
        return em.createQuery("select m.id,m.subject,m.snoozedUntil from MailSummary m where m.source in :sources and (m.inboxRead=false or m.inboxRead is null) and m.snoozedUntil<=:now and m.reminderNotifiedAt is null order by m.snoozedUntil",Object[].class)
            .setParameter("sources",MailWindow.SOURCES).setParameter("now",System.currentTimeMillis()).setMaxResults(50).getResultList().stream()
            .map(r -> Map.<String,Object>of("id",r[0],"subject",Objects.toString(r[1],"邮件提醒"),"snoozedUntil",r[2])).toList();
    }
}
