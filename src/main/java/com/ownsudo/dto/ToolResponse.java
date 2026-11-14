package com.ownsudo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.AgentToolEntity;
import java.time.LocalDateTime;

public record ToolResponse(String toolId, String toolName, String description, JsonNode inputSchema,
                           String riskLevel, boolean enabled, LocalDateTime createdAt) {
    public static ToolResponse from(AgentToolEntity tool, ObjectMapper mapper) {
        try { return new ToolResponse(tool.getId(), tool.getToolName(), tool.getDescription(),
                mapper.readTree(tool.getInputSchemaJson()), tool.getRiskLevel(), tool.isEnabled(), tool.getCreatedAt()); }
        catch (Exception e) { throw new IllegalStateException("Stored tool schema is invalid", e); }
    }
}