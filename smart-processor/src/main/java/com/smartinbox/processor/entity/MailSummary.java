package com.smartinbox.processor.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "mail_summary", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mail_summary_source_external", columnNames = { "source", "external_id" })
}, indexes = {
        @Index(name = "ix_mail_received", columnList = "createdTime"),
        @Index(name = "ix_mail_source_received", columnList = "source,createdTime"),
        @Index(name = "ix_mail_snooze", columnList = "snoozed_until")
})
public class MailSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ✅ Stable external id from upstream email (Message-ID hash / UID).
     * Must be non-empty for new records.
     * Note: keep nullable=true to avoid breaking existing H2 data; we enforce
     * non-empty in code.
     */
    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(length = 1000)
    private String subject;

    @Column(name = "original_subject", length = 1000)
    private String originalSubject;

    private String sender;

    /** AI tag only (NOT for tab classification) */
    private String category;

    @Column(length = 2000)
    private String summary;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String content;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String htmlContent;
    public String getHtmlContent() { return htmlContent; }
    public void setHtmlContent(String htmlContent) { this.htmlContent = htmlContent; }

    private Integer urgency;

    private String action;

    private String status;

    private LocalDateTime createdTime;

    private String source;

    @Column(name = "inbox_read")
    private Boolean inboxRead = false;

    @Column
    private Boolean starred = false;

    private LocalDateTime updatedTime;
    @Column(name = "snoozed_until")
    private Long snoozedUntil;
    private Long reminderNotifiedAt;
    private Boolean bodySynced = false;
    public boolean isBodySynced() { return Boolean.TRUE.equals(bodySynced); }
    public void setBodySynced(boolean value) { bodySynced = value; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime value) { updatedTime = value; }
    public Long getSnoozedUntil() { return snoozedUntil; }
    public void setSnoozedUntil(Long value) { snoozedUntil = value; }
    public Long getReminderNotifiedAt() { return reminderNotifiedAt; }
    public void setReminderNotifiedAt(Long value) { reminderNotifiedAt = value; }
    @PreUpdate
    public void touch() { updatedTime = LocalDateTime.now(); }

    @PrePersist
    public void prePersist() {
        touch();
        if (this.createdTime == null) {
            this.createdTime = LocalDateTime.now();
        }
    }

    // ===== Getters & Setters =====

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getOriginalSubject() {
        return originalSubject;
    }

    public void setOriginalSubject(String originalSubject) {
        this.originalSubject = originalSubject;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getUrgency() {
        return urgency;
    }

    public void setUrgency(Integer urgency) {
        this.urgency = urgency;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isInboxRead() {
        return Boolean.TRUE.equals(inboxRead);
    }

    public void setInboxRead(boolean inboxRead) {
        this.inboxRead = inboxRead;
    }

    public boolean isStarred() {
        return Boolean.TRUE.equals(starred);
    }

    public void setStarred(boolean starred) {
        this.starred = starred;
    }
}
