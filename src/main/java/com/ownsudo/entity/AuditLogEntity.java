package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "log_time", length = 64)
    private String time;

    @Column(name = "agent_name", length = 128)
    private String agentName;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters;

    @Column(length = 64)
    private String model;

    @Column(length = 32)
    private String duration;

    @Column(name = "tokens_in")
    private Integer tokensIn = 0;

    @Column(name = "tokens_out")
    private Integer tokensOut = 0;

    @Column(name = "is_real_usage")
    private Boolean isRealUsage = false;

    @Column(length = 32)
    private String status = "SUCCESS";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public AuditLogEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public Boolean getIsRealUsage() { return isRealUsage; }
    public void setIsRealUsage(Boolean isRealUsage) { this.isRealUsage = isRealUsage; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public Integer getTokensIn() { return tokensIn; }
    public void setTokensIn(Integer tokensIn) { this.tokensIn = tokensIn; }

    public Integer getTokensOut() { return tokensOut; }
    public void setTokensOut(Integer tokensOut) { this.tokensOut = tokensOut; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
