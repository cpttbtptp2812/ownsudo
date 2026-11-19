package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownsudo.entity.TraceSpanEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
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
                       SUM(CASE WHEN usage_source = 'PROVIDER' THEN input_tokens ELSE NULL END) AS input_tokens,
                       SUM(CASE WHEN usage_source = 'PROVIDER' THEN output_tokens ELSE NULL END) AS output_tokens,
                       SUM(CASE WHEN usage_source = 'PROVIDER' THEN total_tokens ELSE NULL END) AS total_tokens,
                       SUM(CASE WHEN usage_source = 'PROVIDER' THEN 1 ELSE 0 END) AS provider_usage_count,
                       SUM(CASE WHEN usage_source = 'ESTIMATED' THEN 1 ELSE 0 END) AS estimated_usage_count,
                       MIN(CASE WHEN parent_span_id IS NULL THEN name ELSE NULL END) AS root_name,
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
                : Duration.between(startedAt, endedAt).toMillis();

        String sessionId = jsonAttribute(span.getAttributesJson(), "session.id");
        String conversationId = jsonAttribute(span.getAttributesJson(), "conversation.id");
        Object rootName = aggregate.get("root_name");
        String name = rootName instanceof String value && !value.isBlank()
                ? value : span.getName() == null || span.getName().isBlank() ? "Agent run" : span.getName();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> values = new java.util.HashMap<>();
        values.put("id", runPk);
        values.put("agentId", agentId);
        values.put("runId", runId);
        values.put("traceId", span.getTraceId());
        values.put("sessionId", sessionId);
        values.put("conversationId", conversationId);
        values.put("name", name);
        values.put("status", status);
        values.put("startedAt", startedAt);
        values.put("endedAt", endedAt);
        values.put("duration", duration);
        values.put("spanCount", spanCount);
        values.put("errorCount", errorCount);
        values.put("inputTokens", aggregate.get("input_tokens"));
        values.put("outputTokens", aggregate.get("output_tokens"));
        values.put("totalTokens", aggregate.get("total_tokens"));
        values.put("usageSource", usageSource(aggregate));
        persistRun(values, now);

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
        payload.put("usageSource", span.getUsageSource());
        try {
            jdbc.update("""
                    INSERT INTO agent_run_event
                        (id, agent_id, run_id, trace_id, span_id, event_type, status,
                         event_time, payload_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, stableId("evt", agentId + ":" + span.getTraceId() + ":" + span.getSpanId()
                    + ":" + span.getStatus() + ":" + span.getEndedAt()),
                    agentId, runId, span.getTraceId(), span.getSpanId(), eventType, span.getStatus(),
                    eventTime, mapper.writeValueAsString(payload), now);
        } catch (DuplicateKeyException ignored) {
            // Re-delivery of the same span lifecycle event is idempotent.
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize Run event", e);
        }
    }

    private void persistRun(Map<String, Object> values, LocalDateTime now) {
        int existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE agent_id = ? AND run_id = ?",
                Integer.class, values.get("agentId"), values.get("runId"));
        if (existing > 0) {
            updateRun(values, now);
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO agent_run
                        (id, agent_id, run_id, trace_id, session_id, conversation_id, name, status,
                         started_at, ended_at, duration_ms, span_count, error_count,
                         input_tokens, output_tokens, total_tokens, usage_source, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, values.get("id"), values.get("agentId"), values.get("runId"), values.get("traceId"),
                    emptyToNull((String) values.get("sessionId")), emptyToNull((String) values.get("conversationId")),
                    values.get("name"), values.get("status"), values.get("startedAt"), values.get("endedAt"),
                    values.get("duration"), values.get("spanCount"), values.get("errorCount"),
                    values.get("inputTokens"), values.get("outputTokens"), values.get("totalTokens"),
                    values.get("usageSource"), now, now);
        } catch (DuplicateKeyException concurrentInsert) {
            updateRun(values, now);
        }
    }

    private void updateRun(Map<String, Object> values, LocalDateTime now) {
        jdbc.update("""
                UPDATE agent_run
                   SET trace_id = ?, session_id = COALESCE(?, session_id),
                       conversation_id = COALESCE(?, conversation_id), name = ?, status = ?,
                       started_at = ?, ended_at = ?, duration_ms = ?, span_count = ?,
                       error_count = ?, input_tokens = ?, output_tokens = ?, total_tokens = ?,
                       usage_source = ?, updated_at = ?
                 WHERE agent_id = ? AND run_id = ?
                """, values.get("traceId"), emptyToNull((String) values.get("sessionId")),
                emptyToNull((String) values.get("conversationId")), values.get("name"), values.get("status"),
                values.get("startedAt"), values.get("endedAt"), values.get("duration"),
                values.get("spanCount"), values.get("errorCount"), values.get("inputTokens"),
                values.get("outputTokens"), values.get("totalTokens"), values.get("usageSource"),
                now, values.get("agentId"), values.get("runId"));
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
        int providerCount = ((Number) aggregate.get("provider_usage_count")).intValue();
        if (providerCount > 0) return "PROVIDER";
        int estimatedCount = ((Number) aggregate.get("estimated_usage_count")).intValue();
        return estimatedCount > 0 ? "ESTIMATED" : "MISSING";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stableId(String prefix, String value) {
        return prefix + "-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
