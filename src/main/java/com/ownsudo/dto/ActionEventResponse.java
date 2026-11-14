package com.ownsudo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEventEntity;
import java.time.LocalDateTime;

public record ActionEventResponse(String eventId, String actionId, String eventType,
                                  JsonNode details, LocalDateTime createdAt) {
    public static ActionEventResponse from(ActionEventEntity event, ObjectMapper mapper) {
        try {
            JsonNode details = event.getDetailJson() == null ? null : mapper.readTree(event.getDetailJson());
            return new ActionEventResponse(event.getId(), event.getActionId(), event.getEventType(), details, event.getCreatedAt());
        } catch (Exception e) {
            throw new IllegalStateException("Stored Action event JSON is invalid", e);
        }
    }
}