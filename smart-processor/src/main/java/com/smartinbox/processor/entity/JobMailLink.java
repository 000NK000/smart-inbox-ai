package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name="job_mail_link", uniqueConstraints=@UniqueConstraint(name="uk_job_mail_link", columnNames={"application_id","mail_id"}),
        indexes=@Index(name="ix_job_mail_mail", columnList="mail_id"))
public class JobMailLink {
    @Id @Column(length=160) private String id;
    @Column(name="application_id", nullable=false, length=80) private String applicationId;
    @Column(name="mail_id", nullable=false) private Long mailId;
    @Column(nullable=false) private Long linkedAt;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getApplicationId(){return applicationId;} public void setApplicationId(String v){applicationId=v;}
    public Long getMailId(){return mailId;} public void setMailId(Long v){mailId=v;}
    public Long getLinkedAt(){return linkedAt;} public void setLinkedAt(Long v){linkedAt=v;}
}
