package com.ownsudo.dto;

import com.ownsudo.entity.ActionEntity;
import java.time.LocalDateTime;

public record ActionResponse(
        String actionId,
        String agentId,
        String toolName,
        String status,
        String decision,
        String riskLevel,
        String argumentsJson,
        String metadataJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ActionResponse from(ActionEntity action) {
        return new ActionResponse(
                action.getId(),
                action.getAgentId(),
                action.getToolName(),
                action.getStatus(),
                action.getDecision(),
                action.getRiskLevel(),
                action.getArgumentsJson(),
                action.getMetadataJson(),
                action.getCreatedAt(),
                action.getUpdatedAt());
    }
}
