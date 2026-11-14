package com.ownsudo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.TraceSpanEntity;
import java.time.LocalDateTime;

public record TraceSpanResponse(
        String traceId, String spanId, String parentSpanId, String runId,
        String name, String spanKind, String status,
        LocalDateTime startedAt, LocalDateTime endedAt, Long durationMs,
        Long inputTokens, Long outputTokens, Long totalTokens, String usageSource,
        JsonNode attributes
) {
    public static TraceSpanResponse from(TraceSpanEntity span, ObjectMapper mapper) {
        try {
            JsonNode attributes = span.getAttributesJson() == null ? null : mapper.readTree(span.getAttributesJson());
            return new TraceSpanResponse(span.getTraceId(), span.getSpanId(), span.getParentSpanId(),
                    span.getRunId(), span.getName(), span.getSpanKind(), span.getStatus(),
                    span.getStartedAt(), span.getEndedAt(), span.getDurationMs(),
                    span.getInputTokens(), span.getOutputTokens(), span.getTotalTokens(),
                    span.getUsageSource(), attributes);
        } catch (Exception e) {
            throw new IllegalStateException("Stored trace attributes JSON is invalid", e);
        }
    }
}
