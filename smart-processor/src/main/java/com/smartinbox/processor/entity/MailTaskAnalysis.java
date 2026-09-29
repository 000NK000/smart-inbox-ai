package com.smartinbox.processor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mail_task_analysis")
public class MailTaskAnalysis {
    @Id private Long mailId;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Lob @Column(nullable = false, columnDefinition = "CLOB") private String tasksJson;
    private Instant analyzedAt;
    public Long getMailId() { return mailId; }
    public void setMailId(Long value) { mailId = value; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String value) { fingerprint = value; }
    public String getTasksJson() { return tasksJson; }
    public void setTasksJson(String value) { tasksJson = value; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(Instant value) { analyzedAt = value; }
}
