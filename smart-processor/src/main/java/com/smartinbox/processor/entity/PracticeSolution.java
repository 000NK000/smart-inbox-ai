package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "practice_solution")
public class PracticeSolution {
    @Id private Integer number;
    @Lob @Column(nullable = false, columnDefinition = "CLOB") private String content = "";
    @Column(nullable = false) private long updatedAt;
    @Version private Long version;

    public Integer getNumber() { return number; }
    public void setNumber(Integer value) { number = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { updatedAt = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
