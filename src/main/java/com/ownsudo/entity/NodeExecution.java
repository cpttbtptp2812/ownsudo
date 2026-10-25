package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "node_execution")
public class NodeExecution {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "instance_id", nullable = false, length = 64)
    private String instanceId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_name", length = 128)
    private String nodeName;

    @Column(name = "node_type", length = 64)
    private String nodeType; // LLM_REASONING, MCP_TOOL_CALL, HUMAN_APPROVAL_2PC, POLICY_VERIFICATION, SQL_EXECUTOR

    @Column(nullable = false, length = 32)
    private String status; // PENDING, RUNNING, SUCCESS, BLOCKED, FAILED

    @Column(columnDefinition = "LONGTEXT")
    private String inputPayload;

    @Column(columnDefinition = "LONGTEXT")
    private String outputPayload;

    @Column(name = "cost_tokens")
    private Integer costTokens = 0;

    @Column(name = "latency_ms")
    private Long latencyMs = 0L;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @PrePersist
    public void prePersist() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }

    public NodeExecution() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String inputPayload) { this.inputPayload = inputPayload; }

    public String getOutputPayload() { return outputPayload; }
    public void setOutputPayload(String outputPayload) { this.outputPayload = outputPayload; }

    public Integer getCostTokens() { return costTokens; }
    public void setCostTokens(Integer costTokens) { this.costTokens = costTokens; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
