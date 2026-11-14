package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.IngestTraceSpanRequest;
import com.ownsudo.entity.TraceSpanEntity;
import com.ownsudo.repository.TraceSpanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class TraceSpanService {
    private static final int MAX_ATTRIBUTES_BYTES = 32 * 1024;
    private final TraceSpanRepository repository;
    private final ObjectMapper mapper;

    public TraceSpanService(TraceSpanRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public TraceSpanEntity ingest(String agentId, IngestTraceSpanRequest request) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("Authenticated Agent identity is required");
        if (request == null) throw new IllegalArgumentException("Request body is required");
        String traceId = required(request.traceId(), "traceId", 64);
        String spanId = required(request.spanId(), "spanId", 64);
        String name = required(request.name(), "name", 200);
        String parent = optional(request.parentSpanId(), 64, "parentSpanId");
        String runId = optional(request.runId(), 64, "runId");
        String kind = normalized(request.spanKind(), "INTERNAL", "spanKind",
                List.of("INTERNAL", "CLIENT", "SERVER", "PRODUCER", "CONSUMER"));
        String status = normalized(request.status(), "UNSET", "status",
                List.of("UNSET", "OK", "ERROR"));
        String usageSource = normalized(request.usageSource(), "MISSING", "usageSource",
                List.of("MISSING", "PROVIDER", "ESTIMATED"));
        Instant start = request.startedAt();
        if (start == null) throw new IllegalArgumentException("startedAt is required");
        Instant end = request.endedAt();
        if (end != null && end.isBefore(start)) throw new IllegalArgumentException("endedAt must not be before startedAt");
        Long input = nonNegative(request.inputTokens(), "inputTokens");
        Long output = nonNegative(request.outputTokens(), "outputTokens");
        Long total = nonNegative(request.totalTokens(), "totalTokens");
        if (total == null && input != null && output != null) total = Math.addExact(input, output);
        if ("PROVIDER".equals(usageSource) && input == null && output == null && total == null)
            throw new IllegalArgumentException("PROVIDER usageSource requires at least one provider-reported token count");

        JsonNode attributes = request.attributes();
        String attributesJson = null;
        if (attributes != null && !attributes.isNull()) {
            if (!attributes.isObject()) throw new IllegalArgumentException("attributes must be a JSON object");
            try { attributesJson = mapper.writeValueAsString(attributes); }
            catch (Exception e) { throw new IllegalArgumentException("attributes cannot be serialized", e); }
            if (attributesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ATTRIBUTES_BYTES)
                throw new IllegalArgumentException("attributes must not exceed 32 KiB; do not send prompt/response bodies by default");
        }

        var existing = repository.findByAgentIdAndTraceIdAndSpanId(agentId, traceId, spanId);
        if (existing.isPresent()) {
            TraceSpanEntity old = existing.get();
            if (!old.getName().equals(name) || !old.getStartedAt().equals(LocalDateTime.ofInstant(start, ZoneOffset.UTC)))
                throw new SpanConflictException("spanId already exists in this trace with different content");
            return old;
        }

        TraceSpanEntity span = new TraceSpanEntity();
        span.setId("spn-" + UUID.randomUUID());
        span.setAgentId(agentId);
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setParentSpanId(parent);
        span.setRunId(runId);
        span.setName(name);
        span.setSpanKind(kind);
        span.setStatus(status);
        span.setStartedAt(LocalDateTime.ofInstant(start, ZoneOffset.UTC));
        span.setEndedAt(end == null ? null : LocalDateTime.ofInstant(end, ZoneOffset.UTC));
        span.setDurationMs(end == null ? null : Duration.between(start, end).toMillis());
        span.setInputTokens(input);
        span.setOutputTokens(output);
        span.setTotalTokens(total);
        span.setUsageSource(usageSource);
        span.setAttributesJson(attributesJson);
        return repository.save(span);
    }

    @Transactional(readOnly = true)
    public List<TraceSpanEntity> list(String agentId, String traceId) {
        return repository.findAllByAgentIdAndTraceIdOrderByStartedAtAsc(agentId, traceId);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(field + " is required and must not exceed " + max + " characters");
        return value.trim();
    }
    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw new IllegalArgumentException(field + " must not exceed " + max + " characters");
        return value.trim();
    }
    private static String normalized(String value, String fallback, String field, List<String> allowed) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " must be one of " + allowed);
        return normalized;
    }
    private static Long nonNegative(Long value, String field) {
        if (value != null && value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
    public static class SpanConflictException extends RuntimeException {
        public SpanConflictException(String message) { super(message); }
    }
}
