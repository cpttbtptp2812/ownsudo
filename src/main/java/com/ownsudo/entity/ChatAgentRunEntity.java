package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_agent_run", indexes = {
        @Index(name = "idx_chat_run_session", columnList = "session_id, started_at"),
        @Index(name = "idx_chat_run_status", columnList = "status, started_at")
})
public class ChatAgentRunEntity {
    @Id
    @Column(name = "run_id", length = 64)
    private String runId;
    @Column(name = "trace_id", nullable = false, unique = true, length = 64)
    private String traceId;
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;
    @Column(name = "agent_id", length = 64)
    private String agentId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 128)
    private String model;
    @Column(length = 128)
    private String provider;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;
    @Column(name = "ended_at")
    private LocalDateTime endedAt;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "input_tokens")
    private Long inputTokens;
    @Column(name = "output_tokens")
    private Long outputTokens;
    @Column(name = "total_tokens")
    private Long totalTokens;
    @Column(name = "usage_source", nullable = false, length = 24)
    private String usageSource;
    @Column(name = "error_message", length = 2000)
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ChatAgentRunEntity() {}
}