package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.TraceSpanResponse;
import com.ownsudo.repository.TraceSpanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.ownsudo.security.AgentApiKeyFilter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {
    private final JdbcTemplate jdbc;
    private final TraceSpanRepository spans;
    private final ObjectMapper mapper;

    public RunController(JdbcTemplate jdbc, TraceSpanRepository spans, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.spans = spans;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> recent(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId) {
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT run_id AS runId, trace_id AS traceId, session_id AS sessionId,
                       conversation_id AS conversationId, name, status, started_at AS startedAt,
                       ended_at AS endedAt, duration_ms AS durationMs, span_count AS spanCount,
                       error_count AS errorCount, input_tokens AS inputTokens,
                       output_tokens AS outputTokens, total_tokens AS totalTokens,
                       usage_source AS usageSource
                  FROM agent_run WHERE agent_id = ?
                 ORDER BY started_at DESC LIMIT 200
                """, agentId));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> detail(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String runId) {
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT run_id AS runId, trace_id AS traceId, session_id AS sessionId,
                       conversation_id AS conversationId, name, status, started_at AS startedAt,
                       ended_at AS endedAt, duration_ms AS durationMs, span_count AS spanCount,
                       error_count AS errorCount, input_tokens AS inputTokens,
                       output_tokens AS outputTokens, total_tokens AS totalTokens,
                       usage_source AS usageSource
                  FROM agent_run WHERE agent_id = ? AND run_id = ?
                """, agentId, runId);
        if (runs.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> run = runs.get(0);
        String traceId = String.valueOf(run.get("traceId"));
        var traceSpans = spans.findAllByAgentIdAndTraceIdOrderByStartedAtAsc(agentId, traceId)
                .stream().map(span -> TraceSpanResponse.from(span, mapper)).toList();
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type AS eventType, status, event_time AS eventTime,
                       span_id AS spanId, payload_json AS payload
                  FROM agent_run_event WHERE agent_id = ? AND run_id = ?
                 ORDER BY event_time ASC, id ASC
                """, agentId, runId);
        return ResponseEntity.ok(Map.of("run", run, "spans", traceSpans, "events", events));
    }
}
