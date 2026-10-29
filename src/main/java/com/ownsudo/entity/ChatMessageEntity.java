package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 32)
    private String role; // user, assistant

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "trace_steps_json", columnDefinition = "LONGTEXT")
    private String traceStepsJson;

    @Column(name = "tool_calls_json", columnDefinition = "LONGTEXT")
    private String toolCallsJson;

    @Column(name = "approval_ticket_json", columnDefinition = "LONGTEXT")
    private String approvalTicketJson;

    @Column(name = "trace_json", columnDefinition = "TEXT")
    private String traceJson;

    private Integer tokens;

    @Column(length = 32)
    private String duration;

    @Column(name = "is_cached")
    private Boolean isCached = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public ChatMessageEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getCitationsJson() { return citationsJson; }
    public void setCitationsJson(String citationsJson) { this.citationsJson = citationsJson; }

    public String getTraceStepsJson() { return traceStepsJson; }
    public void setTraceStepsJson(String traceStepsJson) { this.traceStepsJson = traceStepsJson; }

    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; }

    public String getApprovalTicketJson() { return approvalTicketJson; }
    public void setApprovalTicketJson(String approvalTicketJson) { this.approvalTicketJson = approvalTicketJson; }

    public String getTraceJson() { return traceJson; }
    public void setTraceJson(String traceJson) { this.traceJson = traceJson; }

    public Integer getTokens() { return tokens; }
    public void setTokens(Integer tokens) { this.tokens = tokens; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public Boolean getIsCached() { return isCached; }
    public void setIsCached(Boolean isCached) { this.isCached = isCached; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
