package com.smartinbox.processor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="job_mail_suggestion", indexes=@Index(name="ix_job_suggestion_status", columnList="status,analyzed_at"))
public class JobMailSuggestion {
    @Id @Column(name="mail_id") private Long mailId;
    @Column(nullable=false,length=64) private String fingerprint;
    @Column(nullable=false) private Boolean recruitment;
    @Column(length=200) private String company;
    @Column(length=300) private String role;
    @Column(length=20) private String suggestedStage;
    @Column(length=20) private String suggestedResult;
    @Column(length=1000) private String summaryChinese;
    @Column(length=1000) private String evidence;
    @Lob @Column(columnDefinition="CLOB") private String preparationsJson;
    @Column(nullable=false,length=20) private String status;
    @Column(length=80) private String applicationId;
    @Column(name="analyzed_at",nullable=false) private Instant analyzedAt;
    @Version @Column(nullable=false,columnDefinition="bigint default 0") private Long version=0L;
    public Long getMailId(){return mailId;} public void setMailId(Long v){mailId=v;}
    public String getFingerprint(){return fingerprint;} public void setFingerprint(String v){fingerprint=v;}
    public boolean isRecruitment(){return Boolean.TRUE.equals(recruitment);} public void setRecruitment(boolean v){recruitment=v;}
    public String getCompany(){return company==null?"":company;} public void setCompany(String v){company=v;}
    public String getRole(){return role==null?"":role;} public void setRole(String v){role=v;}
    public String getSuggestedStage(){return suggestedStage;} public void setSuggestedStage(String v){suggestedStage=v;}
    public String getSuggestedResult(){return suggestedResult;} public void setSuggestedResult(String v){suggestedResult=v;}
    public String getSummaryChinese(){return summaryChinese==null?"":summaryChinese;} public void setSummaryChinese(String v){summaryChinese=v;}
    public String getEvidence(){return evidence==null?"":evidence;} public void setEvidence(String v){evidence=v;}
    public String getPreparationsJson(){return preparationsJson==null?"[]":preparationsJson;} public void setPreparationsJson(String v){preparationsJson=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getApplicationId(){return applicationId;} public void setApplicationId(String v){applicationId=v;}
    public Instant getAnalyzedAt(){return analyzedAt;} public void setAnalyzedAt(Instant v){analyzedAt=v;}
    public Long getVersion(){return version==null?0L:version;} public void setVersion(Long v){version=v;}
}
