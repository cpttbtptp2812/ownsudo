package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ownsudo.service.ChatRunRecorder;
import com.ownsudo.service.LlmClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chat/runs")
public class ChatRunController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ChatRunRecorder recorder;
    private final LlmClientService llm;

    public ChatRunController(JdbcTemplate jdbc, ObjectMapper mapper, ChatRunRecorder recorder, LlmClientService llm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.recorder = recorder;
        this.llm = llm;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT run_id AS runId, trace_id AS traceId, session_id AS sessionId,
                       agent_id AS agentId, name, model, provider, status,
                       started_at AS startedAt, ended_at AS endedAt, duration_ms AS durationMs,
                       input_tokens AS inputTokens, output_tokens AS outputTokens,
                       total_tokens AS totalTokens, usage_source AS usageSource,
                       (SELECT COUNT(*) FROM chat_agent_run_span s WHERE s.run_id = chat_agent_run.run_id) AS spanCount,
                       (SELECT COUNT(*) FROM chat_agent_run_span s WHERE s.run_id = chat_agent_run.run_id AND s.status = 'ERROR') AS errorCount
                  FROM chat_agent_run WHERE session_id = ?
                 ORDER BY started_at DESC LIMIT 100
                """, sessionId));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> detail(@PathVariable String runId) {
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT run_id AS runId, trace_id AS traceId, session_id AS sessionId,
                       agent_id AS agentId, name, model, provider, status,
                       started_at AS startedAt, ended_at AS endedAt, duration_ms AS durationMs,
                       input_tokens AS inputTokens, output_tokens AS outputTokens,
                       total_tokens AS totalTokens, usage_source AS usageSource, error_message AS errorMessage
                  FROM chat_agent_run WHERE run_id = ?
                """, runId);
        if (runs.isEmpty()) return ResponseEntity.notFound().build();
        List<Map<String, Object>> spans = jdbc.queryForList("""
                SELECT span_id AS spanId, parent_span_id AS parentSpanId, name, span_kind AS spanKind,
                       status, started_at AS startedAt, ended_at AS endedAt, duration_ms AS durationMs,
                       input_tokens AS inputTokens, output_tokens AS outputTokens, usage_source AS usageSource,
                       attributes_json AS attributes
                  FROM chat_agent_run_span WHERE run_id = ? ORDER BY started_at ASC, span_id ASC
                """, runId);
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT sequence_no AS sequence, event_type AS eventType, status,
                       event_time AS eventTime, payload_json AS payload
                  FROM chat_agent_run_event WHERE run_id = ? ORDER BY sequence_no ASC
                """, runId);
        List<Map<String, Object>> evaluations = jdbc.queryForList("""
                SELECT evaluation_id AS evaluationId, judge_model AS judgeModel, rubric, scores_json AS scores,
                       created_at AS createdAt
                  FROM chat_agent_run_evaluation WHERE run_id = ? ORDER BY created_at DESC LIMIT 50
                """, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", runs.get(0));
        result.put("spans", spans);
        result.put("events", events);
        result.put("evaluations", evaluations);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{runId}/judge")
    public ResponseEntity<?> judge(@PathVariable String runId, @RequestBody(required = false) Map<String, Object> request) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT trace_id FROM chat_agent_run WHERE run_id = ?", runId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        String traceId = String.valueOf(rows.get(0).get("trace_id"));
        List<Map<String, Object>> evidence = jdbc.queryForList("""
                SELECT sequence_no AS sequence, event_type AS eventType, status, event_time AS eventTime, payload_json AS payload
                  FROM chat_agent_run_event WHERE run_id = ? ORDER BY sequence_no ASC
                """, runId);
        try {
            String task = String.valueOf(request == null ? "" : request.getOrDefault("task", ""));
            String answer = String.valueOf(request == null ? "" : request.getOrDefault("answer", ""));
            String rubric = String.valueOf(request == null ? "任务完成度、指令遵循、工具使用合理性、执行可靠性" :
                    request.getOrDefault("rubric", "任务完成度、指令遵循、工具使用合理性、执行可靠性"));
            String jsonEvidence = mapper.writeValueAsString(evidence);
            var scores = llm.judgeAgentRun(task, answer, jsonEvidence, rubric);
            String judgeModel = llm.getConfig("EVAL_JUDGE_MODEL", llm.getConfig("LLM_MODEL", "configured-model"));
            String evaluationId = UUID.randomUUID().toString();
            var safeScores = sanitize(scores);
            jdbc.update("""
                    INSERT INTO chat_agent_run_evaluation
                        (evaluation_id, run_id, judge_model, rubric, scores_json, created_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, evaluationId, runId, judgeModel, rubric, mapper.writeValueAsString(safeScores));
            return ResponseEntity.ok(Map.of("evaluationId", evaluationId, "runId", runId,
                    "traceId", traceId, "judgeModel", judgeModel, "rubric", rubric, "scores", safeScores));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", Map.of("code", "JUDGE_FAILED",
                    "message", e.getMessage() == null ? "Judge provider unavailable" : e.getMessage())));
        }
    }

    private JsonNode sanitize(JsonNode value) {
        if (value == null) return mapper.createObjectNode();
        if (value.isObject()) {
            var safe = mapper.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("reasoning") || key.startsWith("analysis") ||
                        key.startsWith("chain_of_thought") || key.startsWith("internal_thought") ||
                        key.startsWith("scratchpad") || key.equals("thoughts")) return;
                JsonNode child = entry.getValue();
                if (child != null && child.isObject()) {
                    String channel = child.path("channel").asText(child.path("type").asText(child.path("kind").asText("")));
                    String role = child.path("role").asText("");
                    if ((role.isBlank() || "assistant".equalsIgnoreCase(role)) && isInternalChannel(channel)) return;
                }
                safe.set(entry.getKey(), sanitize(child));
            });
            return safe;
        }
        if (value.isArray()) {
            var safe = mapper.createArrayNode();
            value.forEach(item -> {
                if (item != null && item.isObject()) {
                    String channel = item.path("channel").asText(item.path("type").asText(item.path("kind").asText("")));
                    String role = item.path("role").asText("");
                    if ((role.isBlank() || "assistant".equalsIgnoreCase(role)) && isInternalChannel(channel)) return;
                }
                safe.add(sanitize(item));
            });
            return safe;
        }
        return value.deepCopy();
    }

    private boolean isInternalChannel(String channel) {
        String normalized = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        return normalized.contains("analysis") || normalized.contains("reasoning")
                || normalized.contains("thought") || normalized.contains("internal");
    }
}
