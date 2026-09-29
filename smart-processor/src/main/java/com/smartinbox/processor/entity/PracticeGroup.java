package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "practice_group")
public class PracticeGroup {
    @Id @Column(length = 80) private String name;
    @Column(length = 4000) private String note;
    @Version private Long version;

    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
