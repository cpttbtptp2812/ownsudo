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
        JsonNode output,
        String errorCode,
        String errorMessage,
        int attemptCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt) {

    public static ActionResponse from(ActionEntity action, ObjectMapper mapper) {
        try {
            JsonNode arguments = mapper.readTree(action.getArgumentsJson());
            JsonNode metadata = action.getMetadataJson() == null ? null : mapper.readTree(action.getMetadataJson());
            JsonNode output = action.getOutputJson() == null ? null : mapper.readTree(action.getOutputJson());
            return new ActionResponse(action.getId(), action.getAgentId(), action.getToolName(),
                    action.getStatus(), action.getDecision(), action.getRiskLevel(), arguments, metadata,
                    output, action.getErrorCode(), action.getErrorMessage(), action.getAttemptCount(),
                    action.getCreatedAt(), action.getUpdatedAt(), action.getStartedAt(), action.getCompletedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Persisted Action JSON is invalid", e);
        }
    }
}
