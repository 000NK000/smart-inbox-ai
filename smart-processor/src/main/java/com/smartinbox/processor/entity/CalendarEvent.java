package com.smartinbox.processor.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/** A series stores local wall-clock times; occurrences are calculated in its IANA zone. */
@Entity
@Table(name = "calendar_events", indexes = @Index(name = "ix_calendar_bounds", columnList = "bounds_start,bounds_end"))
public class CalendarEvent {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 300) private String title;
    @Column(nullable = false, length = 20) private String kind;
    @Column(nullable = false, length = 40) private String startLocal;
    @Column(nullable = false, length = 40) private String endLocal;
    @Column(nullable = false, length = 80) private String zone;
    @Column(nullable = false, length = 10) private String recurrence;
    @Column(nullable = false) private int weekdayMask;
    private LocalDate repeatUntil;
    @Column(nullable = false, length = 300) private String location;
    @Column(nullable = false, length = 4000) private String notes;
    @Column(name = "bounds_start", nullable = false) private long boundsStart;
    @Column(name = "bounds_end", nullable = false) private long boundsEnd;
    // Every accepted edit advances @Version, including an otherwise identical edit.
    @Column(nullable = false, length = 36) private String revisionNonce;
    @Version private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStartLocal() { return startLocal; }
    public void setStartLocal(String startLocal) { this.startLocal = startLocal; }
    public String getEndLocal() { return endLocal; }
    public void setEndLocal(String endLocal) { this.endLocal = endLocal; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getRecurrence() { return recurrence; }
    public void setRecurrence(String recurrence) { this.recurrence = recurrence; }
    public int getWeekdayMask() { return weekdayMask; }
    public void setWeekdayMask(int weekdayMask) { this.weekdayMask = weekdayMask; }
    public LocalDate getRepeatUntil() { return repeatUntil; }
    public void setRepeatUntil(LocalDate repeatUntil) { this.repeatUntil = repeatUntil; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public long getBoundsStart() { return boundsStart; }
    public void setBoundsStart(long boundsStart) { this.boundsStart = boundsStart; }
    public long getBoundsEnd() { return boundsEnd; }
    public void setBoundsEnd(long boundsEnd) { this.boundsEnd = boundsEnd; }
    public String getRevisionNonce() { return revisionNonce; }
    public void setRevisionNonce(String revisionNonce) { this.revisionNonce = revisionNonce; }
    public Long getVersion() { return version; }
}
