package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_run_event", indexes = {
        @Index(name = "idx_run_event_timeline", columnList = "agent_id, run_id, event_time"),
        @Index(name = "idx_run_event_trace", columnList = "agent_id, trace_id, event_time")
})
public class AgentRunEventEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;
    @Column(name = "span_id", length = 64)
    private String spanId;
    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;
    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public AgentRunEventEntity() {}
}
