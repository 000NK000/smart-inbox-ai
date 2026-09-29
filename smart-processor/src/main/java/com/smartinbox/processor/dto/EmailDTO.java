package com.smartinbox.processor.dto;

import java.io.Serializable;

public class EmailDTO implements Serializable {
    private String subject;
    private String sender;
    private String content;
    private String htmlContent;
    public String getHtmlContent() { return htmlContent; }
    public void setHtmlContent(String htmlContent) { this.htmlContent = htmlContent; }
    private Long timestamp;
    private String source;

    // ✅ 稳定去重 ID
    private String externalId;

    public EmailDTO() {
    }

    // 兼容旧构造器
    public EmailDTO(String subject, String sender, String content, Long timestamp, String source) {
        this(subject, sender, content, timestamp, source, null);
    }

    public EmailDTO(String subject, String sender, String content, Long timestamp, String source, String externalId) {
        this.subject = subject;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
        this.source = source;
        this.externalId = externalId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    @Override
    public String toString() {
        return "EmailDTO{" +
                "subject='" + subject + '\'' +
                ", sender='" + sender + '\'' +
                ", content='" + (content != null && content.length() > 20 ? content.substring(0, 20) + "..." : content)
                + '\'' +
                ", timestamp=" + timestamp +
                ", source='" + source + '\'' +
                ", externalId='" + externalId + '\'' +
                '}';
    }
}
