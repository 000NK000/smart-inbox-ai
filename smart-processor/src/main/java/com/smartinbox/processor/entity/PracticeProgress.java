package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "practice_progress")
public class PracticeProgress {
    @Id private Integer number;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 80) private String topic;
    @Column(nullable = false, length = 8) private String status;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private int greenCount;
    @Column(nullable = false) private int yellowCount;
    @Column(nullable = false) private int redCount;
    @Column(nullable = false) private int totalMinutes;
    @Column(nullable = false) private int reviewStage;
    @Column(nullable = false) private long lastPracticedAt;
    private Long nextReviewAt;
    @Column(length = 24) private String mistake;
    @Column(length = 1000) private String note;
    @Version private Long version;

    public Integer getNumber() { return number; }
    public void setNumber(Integer value) { number = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getTopic() { return topic; }
    public void setTopic(String value) { topic = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public int getGreenCount() { return greenCount; }
    public void setGreenCount(int value) { greenCount = value; }
    public int getYellowCount() { return yellowCount; }
    public void setYellowCount(int value) { yellowCount = value; }
    public int getRedCount() { return redCount; }
    public void setRedCount(int value) { redCount = value; }
    public int getTotalMinutes() { return totalMinutes; }
    public void setTotalMinutes(int value) { totalMinutes = value; }
    public int getReviewStage() { return reviewStage; }
    public void setReviewStage(int value) { reviewStage = value; }
    public long getLastPracticedAt() { return lastPracticedAt; }
    public void setLastPracticedAt(long value) { lastPracticedAt = value; }
    public Long getNextReviewAt() { return nextReviewAt; }
    public void setNextReviewAt(Long value) { nextReviewAt = value; }
    public String getMistake() { return mistake; }
    public void setMistake(String value) { mistake = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
