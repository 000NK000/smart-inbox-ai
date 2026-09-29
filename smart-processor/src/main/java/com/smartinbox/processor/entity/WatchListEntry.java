package com.smartinbox.processor.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "watch_list_entry", uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "title_key"}))
public class WatchListEntry {
    @Id private String id;
    @Column(nullable = false, length = 300) private String title;
    @Column(name = "title_key", nullable = false, length = 300) private String titleKey;
    @Column(nullable = false, length = 10) private String kind;
    @Column(nullable = false, length = 12) private String status;
    @Column(nullable = false) private int currentEpisode;
    private Integer totalEpisodes;
    @Column(length = 2000) private String notes;
    @Column(length = 2048) private String url;
    @Column(length = 60) private String source;
    @Column(nullable = false) private long createdAt;
    @Column(nullable = false) private long updatedAt;
    @Version private Long version;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    @JsonIgnore public String getTitleKey() { return titleKey; }
    public void setTitleKey(String value) { titleKey = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public int getCurrentEpisode() { return currentEpisode; }
    public void setCurrentEpisode(int value) { currentEpisode = value; }
    public Integer getTotalEpisodes() { return totalEpisodes; }
    public void setTotalEpisodes(Integer value) { totalEpisodes = value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = value; }
    public String getUrl() { return url; }
    public void setUrl(String value) { url = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { createdAt = value; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { updatedAt = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
