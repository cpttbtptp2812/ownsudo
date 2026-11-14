package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_trace_span", uniqueConstraints = @UniqueConstraint(
        name = "uk_trace_span_agent_span", columnNames = {"agent_id", "trace_id", "span_id"}))
public class TraceSpanEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;
    @Column(name = "span_id", nullable = false, length = 64)
    private String spanId;
    @Column(name = "parent_span_id", length = 64)
    private String parentSpanId;
    @Column(name = "run_id", length = 64)
    private String runId;
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
    @Column(name = "total_tokens")
    private Long totalTokens;
    @Column(name = "usage_source", nullable = false, length = 24)
    private String usageSource;
    @Column(name = "attributes_json", columnDefinition = "LONGTEXT")
    private String attributesJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String value) { agentId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String value) { spanId = value; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String value) { parentSpanId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getSpanKind() { return spanKind; }
    public void setSpanKind(String value) { spanKind = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { startedAt = value; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime value) { endedAt = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { durationMs = value; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long value) { inputTokens = value; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long value) { outputTokens = value; }
    public Long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Long value) { totalTokens = value; }
    public String getUsageSource() { return usageSource; }
    public void setUsageSource(String value) { usageSource = value; }
    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String value) { attributesJson = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
