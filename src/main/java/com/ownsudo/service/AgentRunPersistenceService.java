package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownsudo.entity.TraceSpanEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentRunPersistenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentRunPersistenceService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Materializes a stable Run projection from persisted spans. Trace remains the source
     * of truth for span details; this table is the queryable run-level index/summary.
     */
    @Transactional
    public void recordSpan(String agentId, TraceSpanEntity span) {
        String runId = span.getRunId() == null || span.getRunId().isBlank()
                ? span.getTraceId() : span.getRunId();
        String runPk = stableId("run", agentId + ":" + runId);
        Map<String, Object> aggregate = jdbc.queryForMap("""
                SELECT COUNT(*) AS span_count,
                       SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) AS error_count,
                       SUM(CASE WHEN ended_at IS NULL THEN 1 ELSE 0 END) AS open_count,
                       SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens,
                       SUM(total_tokens) AS total_tokens,
                       MIN(started_at) AS started_at,
                       MAX(ended_at) AS ended_at
                  FROM agent_trace_span
                 WHERE agent_id = ? AND trace_id = ?
                """, agentId, span.getTraceId());

        int spanCount = ((Number) aggregate.get("span_count")).intValue();
        int errorCount = ((Number) aggregate.get("error_count")).intValue();
        int openCount = ((Number) aggregate.get("open_count")).intValue();
        LocalDateTime startedAt = asLocalDateTime(aggregate.get("started_at"));
        LocalDateTime endedAt = asLocalDateTime(aggregate.get("ended_at"));
        String status = errorCount > 0 ? "ERROR" : openCount > 0 ? "RUNNING" : "COMPLETED";
        Long duration = endedAt == null || startedAt == null ? null
                : java.time.Duration.between(startedAt, endedAt).toMillis();

        String sessionId = jsonAttribute(span.getAttributesJson(), "session.id");
        String conversationId = jsonAttribute(span.getAttributesJson(), "conversation.id");
        String name = span.getName() == null || span.getName().isBlank() ? "Agent run" : span.getName();

        jdbc.update("""
                INSERT INTO agent_run
                    (id, agent_id, run_id, trace_id, session_id, conversation_id, name, status,
                     started_at, ended_at, duration_ms, span_count, error_count,
                     input_tokens, output_tokens, total_tokens, usage_source, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    trace_id = VALUES(trace_id),
                    session_id = COALESCE(VALUES(session_id), session_id),
                    conversation_id = COALESCE(VALUES(conversation_id), conversation_id),
                    name = VALUES(name), status = VALUES(status),
                    started_at = VALUES(started_at), ended_at = VALUES(ended_at),
                    duration_ms = VALUES(duration_ms), span_count = VALUES(span_count),
                    error_count = VALUES(error_count), input_tokens = VALUES(input_tokens),
                    output_tokens = VALUES(output_tokens), total_tokens = VALUES(total_tokens),
                    usage_source = VALUES(usage_source), updated_at = UTC_TIMESTAMP(6)
                """, runPk, agentId, runId, span.getTraceId(), sessionId, conversationId, name, status,
                startedAt, endedAt, duration, spanCount, errorCount,
                aggregate.get("input_tokens"), aggregate.get("output_tokens"), aggregate.get("total_tokens"),
                usageSource(aggregate));

        String eventType = span.getEndedAt() == null ? "SPAN_STARTED"
                : "ERROR".equals(span.getStatus()) ? "SPAN_FAILED" : "SPAN_COMPLETED";
        LocalDateTime eventTime = span.getEndedAt() == null ? span.getStartedAt() : span.getEndedAt();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("name", span.getName());
        payload.put("spanKind", span.getSpanKind());
        payload.put("status", span.getStatus());
        if (span.getDurationMs() != null) payload.put("durationMs", span.getDurationMs());
        if (span.getInputTokens() != null) payload.put("inputTokens", span.getInputTokens());
        if (span.getOutputTokens() != null) payload.put("outputTokens", span.getOutputTokens());
        if (span.getTotalTokens() != null) payload.put("totalTokens", span.getTotalTokens());
        try {
            jdbc.update("""
                    INSERT IGNORE INTO agent_run_event
                        (id, agent_id, run_id, trace_id, span_id, event_type, status,
                         event_time, payload_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, stableId("evt", agentId + ":" + span.getTraceId() + ":" + span.getSpanId()
                    + ":" + span.getStatus() + ":" + span.getEndedAt()),
                    agentId, runId, span.getTraceId(), span.getSpanId(), eventType, span.getStatus(),
                    eventTime, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize Run event", e);
        }
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private String jsonAttribute(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            var value = mapper.readTree(json).get(key);
            return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String usageSource(Map<String, Object> aggregate) {
        return aggregate.get("input_tokens") != null || aggregate.get("output_tokens") != null
                || aggregate.get("total_tokens") != null ? "PROVIDER" : "MISSING";
    }

    private static String stableId(String prefix, String value) {
        return prefix + "-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
