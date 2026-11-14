package com.ownsudo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;

import java.time.LocalDateTime;

public record ActionResponse(
        String actionId,
        String agentId,
        String toolName,
        String status,
        String decision,
        String riskLevel,
        JsonNode arguments,
        JsonNode metadata,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ActionResponse from(ActionEntity action, ObjectMapper objectMapper) {
        try {
            JsonNode arguments = objectMapper.readTree(action.getArgumentsJson());
            JsonNode metadata = action.getMetadataJson() == null
                    ? null
                    : objectMapper.readTree(action.getMetadataJson());
            return new ActionResponse(
                    action.getId(),
                    action.getAgentId(),
                    action.getToolName(),
                    action.getStatus(),
                    action.getDecision(),
                    action.getRiskLevel(),
                    arguments,
                    metadata,
                    action.getCreatedAt(),
                    action.getUpdatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Persisted Action JSON is invalid", e);
        }
    }
}
