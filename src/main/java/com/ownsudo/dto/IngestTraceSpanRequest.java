package com.ownsudo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record IngestTraceSpanRequest(
        String traceId,
        String spanId,
        String parentSpanId,
        String runId,
        String name,
        String spanKind,
        String status,
        Instant startedAt,
        Instant endedAt,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        String usageSource,
        JsonNode attributes
) {}
