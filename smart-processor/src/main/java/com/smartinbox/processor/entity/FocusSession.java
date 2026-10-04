package com.smartinbox.processor.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "focus_session", indexes = {
        @Index(name = "ix_focus_started", columnList = "started_at"),
        @Index(name = "ix_focus_ended", columnList = "ended_at")
})
public class FocusSession {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 20) private String category;
    @Column(name = "task_id", length = 80) private String taskId;
    @Column(name = "started_at", nullable = false) private Long startedAt;
    @Column(name = "ended_at") private Long endedAt;
    @JsonIgnore @Column(name = "runtime_id", length = 80) private String runtimeId;
    @JsonIgnore @Column(name = "last_heartbeat_at") private Long lastHeartbeatAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getEndedAt() { return endedAt; }
    public void setEndedAt(Long endedAt) { this.endedAt = endedAt; }
    @JsonIgnore public String getRuntimeId() { return runtimeId; }
    public void setRuntimeId(String runtimeId) { this.runtimeId = runtimeId; }
    @JsonIgnore public Long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Long lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
}
