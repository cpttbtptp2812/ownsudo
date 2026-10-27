package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_instance")
public class WorkflowInstance {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(nullable = false, length = 32)
    private String status; // RUNNING, SUSPENDED_APPROVAL, COMPLETED, FAILED

    @Column(name = "triggered_by", length = 64)
    private String triggeredBy;

    @Column(name = "current_node_id", length = 64)
    private String currentNodeId;

    @Column(columnDefinition = "LONGTEXT")
    private String inputParams;

    @Column(columnDefinition = "LONGTEXT")
    private String contextData;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    public void prePersist() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }

    public WorkflowInstance() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }

    public String getInputParams() { return inputParams; }
    public void setInputParams(String inputParams) { this.inputParams = inputParams; }

    public String getContextData() { return contextData; }
    public void setContextData(String contextData) { this.contextData = contextData; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}