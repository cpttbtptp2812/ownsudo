package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_agent_run_span", indexes =
        @Index(name = "idx_chat_run_span_order", columnList = "run_id, started_at"))
public class ChatAgentRunSpanEntity {
    @EmbeddedId
    private ChatAgentRunSpanId id;
    @Column(name = "parent_span_id", length = 64)
    private String parentSpanId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "span_kind", nullable = false, length = 32)
    private String spanKind;
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
    @Column(name = "usage_source", nullable = false, length = 24)
    private String usageSource;
    @Column(name = "attributes_json", columnDefinition = "LONGTEXT")
    private String attributesJson;

    protected ChatAgentRunSpanEntity() {}
}