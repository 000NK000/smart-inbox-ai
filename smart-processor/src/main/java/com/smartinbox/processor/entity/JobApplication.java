package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "job_application", indexes = {
        @Index(name = "ix_job_application_stage", columnList = "stage,updated_at"),
        @Index(name = "ix_job_application_company", columnList = "company")
})
public class JobApplication {
    @Id @Column(length = 80) private String id;
    @Column(nullable = false, length = 200) private String company;
    @Column(nullable = false, length = 300) private String role;
    @Column(nullable = false, length = 20) private String stage;
    @Column(length = 20) private String result;
    @Column(length = 300) private String location;
    @Column(length = 2048) private String jobUrl;
    @Column(length = 4000) private String notes;
    private Long appliedAt;
    private Long nextActionAt;
    @Column(name = "created_at", nullable = false) private Long createdAt;
    @Column(name = "updated_at", nullable = false) private Long updatedAt;
    @Version @Column(nullable = false, columnDefinition = "bigint default 0") private Long version = 0L;

    public String getId(){return id;} public void setId(String v){id=v;}
    public String getCompany(){return company;} public void setCompany(String v){company=v;}
    public String getRole(){return role;} public void setRole(String v){role=v;}
    public String getStage(){return stage;} public void setStage(String v){stage=v;}
    public String getResult(){return result;} public void setResult(String v){result=v;}
    public String getLocation(){return location==null?"":location;} public void setLocation(String v){location=v;}
    public String getJobUrl(){return jobUrl==null?"":jobUrl;} public void setJobUrl(String v){jobUrl=v;}
    public String getNotes(){return notes==null?"":notes;} public void setNotes(String v){notes=v;}
    public Long getAppliedAt(){return appliedAt;} public void setAppliedAt(Long v){appliedAt=v;}
    public Long getNextActionAt(){return nextActionAt;} public void setNextActionAt(Long v){nextActionAt=v;}
    public Long getCreatedAt(){return createdAt;} public void setCreatedAt(Long v){createdAt=v;}
    public Long getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Long v){updatedAt=v;}
    public Long getVersion(){return version==null?0L:version;} public void setVersion(Long v){version=v;}
}
