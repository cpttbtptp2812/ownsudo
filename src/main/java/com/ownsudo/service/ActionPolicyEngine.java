package com.ownsudo.service;

import com.ownsudo.entity.AgentToolEntity;
import org.springframework.stereotype.Component;

@Component
public class ActionPolicyEngine {
    public PolicyDecision evaluate(AgentToolEntity tool) {
        if (tool == null) return new PolicyDecision("DENY", "DENIED", "UNASSESSED", "TOOL_NOT_REGISTERED");
        if (!tool.isEnabled()) return new PolicyDecision("DENY", "DENIED", tool.getRiskLevel(), "TOOL_DISABLED");
        return switch (tool.getRiskLevel()) {
            case "READ_ONLY", "LOW" -> new PolicyDecision("ALLOW", "READY", tool.getRiskLevel(), "LOW_RISK_TOOL");
            case "MEDIUM", "HIGH", "CRITICAL" -> new PolicyDecision("REQUIRE_APPROVAL", "PENDING_APPROVAL", tool.getRiskLevel(), "HUMAN_APPROVAL_REQUIRED");
            default -> new PolicyDecision("DENY", "DENIED", "UNASSESSED", "INVALID_RISK_LEVEL");
        };
    }
    public record PolicyDecision(String decision, String actionStatus, String riskLevel, String reasonCode) {}
}