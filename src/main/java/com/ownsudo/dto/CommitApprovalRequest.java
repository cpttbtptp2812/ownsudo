package com.ownsudo.dto;

public class CommitApprovalRequest {
    private String operatorId = "auditor_sec";
    private String decision; // APPROVED or REJECTED
    private String auditReason;
    private String digitalSignature;

    public CommitApprovalRequest() {}

    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }

    public String getDigitalSignature() { return digitalSignature; }
    public void setDigitalSignature(String digitalSignature) { this.digitalSignature = digitalSignature; }
}
