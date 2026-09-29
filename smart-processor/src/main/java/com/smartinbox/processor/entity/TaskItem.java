package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "task_item", uniqueConstraints = @UniqueConstraint(name = "uk_task_mail_suggestion", columnNames = {"source_mail_id", "source_suggestion_id"}),
        indexes = @Index(name = "ix_task_status_due", columnList = "status,due_at"))
public class TaskItem {

    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(nullable = false)
    private Long createdAt;

    @Column(length = 10, columnDefinition = "varchar(10) default 'NORMAL'") private String priority = "NORMAL";
    @Column(length = 12, columnDefinition = "varchar(12) default 'OPEN'") private String status = "OPEN";
    @Column(name = "due_at") private Long dueAt;
    @Column(nullable = false, columnDefinition = "integer default 0") private Integer rescheduleCount = 0;
    private Long completedAt;
    private Long updatedAt;
    @Column(length = 2000) private String notes;
    @Column(name = "source_mail_id") private Long sourceMailId;
    @Column(name = "source_suggestion_id", length = 80) private String sourceSuggestionId;
    // A default permits ALTER TABLE on the existing three-column local task table.
    @Version @Column(nullable = false, columnDefinition = "bigint default 0") private Long version = 0L;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public String getPriority() { return priority == null ? "NORMAL" : priority; }
    public void setPriority(String value) { priority = value; }
    public String getStatus() { return status == null ? "OPEN" : status; }
    public void setStatus(String value) { status = value; }
    public Long getDueAt() { return dueAt; }
    public void setDueAt(Long value) { dueAt = value; }
    public Integer getRescheduleCount() { return rescheduleCount == null ? 0 : rescheduleCount; }
    public void setRescheduleCount(Integer value) { rescheduleCount = value; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long value) { completedAt = value; }
    public Long getUpdatedAt() { return updatedAt == null ? createdAt : updatedAt; }
    public void setUpdatedAt(Long value) { updatedAt = value; }
    public String getNotes() { return notes == null ? "" : notes; }
    public void setNotes(String value) { notes = value; }
    public Long getSourceMailId() { return sourceMailId; }
    public void setSourceMailId(Long value) { sourceMailId = value; }
    public String getSourceSuggestionId() { return sourceSuggestionId; }
    public void setSourceSuggestionId(String value) { sourceSuggestionId = value; }
    public Long getVersion() { return version == null ? 0L : version; }
    public void setVersion(Long value) { version = value; }
}
