package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.MailSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MailSummaryRepository extends JpaRepository<MailSummary, Long>, JpaSpecificationExecutor<MailSummary> {

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true)
    @org.springframework.data.jpa.repository.Query("update MailSummary m set m.reminderNotifiedAt=:now, m.updatedTime=:changed where m.id=:id and m.snoozedUntil=:expected and m.snoozedUntil<=:now and (m.inboxRead=false or m.inboxRead is null)")
    int acknowledgeReminder(Long id, Long expected, long now, java.time.LocalDateTime changed);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true)
    @org.springframework.data.jpa.repository.Query("update MailSummary m set m.subject=:subject,m.category=:category,m.summary=:summary,m.urgency=:urgency,m.action=:action,m.status=:status,m.updatedTime=:changed where m.id=:id")
    int updateAiFields(Long id,String subject,String category,String summary,Integer urgency,String action,String status,java.time.LocalDateTime changed);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true)
    @org.springframework.data.jpa.repository.Query("update MailSummary m set m.originalSubject=case when m.originalSubject is null or m.originalSubject='' then :subject else m.originalSubject end, m.content=case when :content is not null and :content<>'' then :content else m.content end, m.htmlContent=case when :html is not null and :html<>'' then :html else m.htmlContent end, m.bodySynced=true,m.updatedTime=:changed where m.source=:source and m.externalId=:externalId")
    int enrichBody(String source,String externalId,String subject,String content,String html,java.time.LocalDateTime changed);

    interface TaskPlanRevision {
        long getMailCount();
        java.time.LocalDateTime getLastChanged();
    }
    @org.springframework.data.jpa.repository.Query("select count(m) as mailCount, max(coalesce(m.updatedTime,m.createdTime)) as lastChanged from MailSummary m where m.createdTime >= :cutoff and m.source in :sources and (m.inboxRead=false or m.inboxRead is null)")
    TaskPlanRevision taskPlanRevision(java.time.LocalDateTime cutoff, java.util.Collection<String> sources);

    boolean existsBySourceAndExternalId(String source, String externalId);

    Optional<MailSummary> findFirstBySourceAndExternalId(String source, String externalId);

    @org.springframework.data.jpa.repository.Query("select m from MailSummary m where m.createdTime >= :cutoff and m.source in :sources and (m.inboxRead=false or m.inboxRead is null) order by m.createdTime desc")
    java.util.List<MailSummary> findTaskPlanMails(java.time.LocalDateTime cutoff, java.util.Collection<String> sources);

    java.util.List<MailSummary> findAllByCreatedTimeGreaterThanEqualAndSourceInOrderByCreatedTimeDesc(
            java.time.LocalDateTime cutoff, java.util.Collection<String> sources);
}
