package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "approval_ticket")
public class ApprovalTicket {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "instance_id", nullable = false, length = 64)
    private String instanceId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "policy_level", length = 32)
    private String policyLevel; // P0_CRITICAL, P1_HIGH, P2_STANDARD

    @Column(name = "risk_score")
    private Double riskScore;

    @Column(columnDefinition = "LONGTEXT")
    private String operationSummary;

    @Column(nullable = false, length = 32)
    private String status; // PENDING, APPROVED, REJECTED

    @Column(name = "operator_id", length = 64)
    private String operatorId;

    @Column(name = "signature_hash", length = 128)
    private String signatureHash;

    @Column(name = "audit_reason", length = 512)
    private String auditReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public ApprovalTicket() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getPolicyLevel() { return policyLevel; }
    public void setPolicyLevel(String policyLevel) { this.policyLevel = policyLevel; }

    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }

    public String getOperationSummary() { return operationSummary; }
    public void setOperationSummary(String operationSummary) { this.operationSummary = operationSummary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }

    public String getSignatureHash() { return signatureHash; }
    public void setSignatureHash(String signatureHash) { this.signatureHash = signatureHash; }

    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
}
