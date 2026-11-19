package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_run_identity", columnNames = {"agent_id", "run_id"}),
        indexes = {
                @Index(name = "idx_agent_run_recent", columnList = "agent_id, started_at"),
                @Index(name = "idx_agent_run_trace", columnList = "agent_id, trace_id"),
                @Index(name = "idx_agent_run_session", columnList = "agent_id, session_id")
        })
public class AgentRunEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;
    @Column(name = "session_id", length = 128)
    private String sessionId;
    @Column(name = "conversation_id", length = 128)
    private String conversationId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;
    @Column(name = "ended_at")
    private LocalDateTime endedAt;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "span_count", nullable = false)
    private Integer spanCount = 0;
    @Column(name = "error_count", nullable = false)
    private Integer errorCount = 0;
    @Column(name = "input_tokens")
    private Long inputTokens;
    @Column(name = "output_tokens")
    private Long outputTokens;
    @Column(name = "total_tokens")
    private Long totalTokens;
    @Column(name = "usage_source", nullable = false, length = 24)
    private String usageSource;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    public AgentRunEntity() {}
}
