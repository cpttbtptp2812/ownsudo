package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentRunEvaluationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentRunEvaluationService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> save(
            String agentId,
            String traceId,
            String judgeModel,
            String rubric,
            JsonNode scores) throws Exception {
        List<Map<String, Object>> matchingRuns = jdbc.queryForList(
                "SELECT run_id FROM agent_run WHERE agent_id = ? AND trace_id = ? ORDER BY started_at DESC LIMIT 1",
                agentId, traceId);
        String runId = matchingRuns.isEmpty() ? null : String.valueOf(matchingRuns.get(0).get("run_id"));
        String evaluationId = UUID.randomUUID().toString();
        LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);
        JsonNode safeScores = sanitizeScores(scores);
        jdbc.update("""
                INSERT INTO agent_run_evaluation
                    (evaluation_id, agent_id, run_id, trace_id, judge_model, rubric, scores_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, evaluationId, agentId, runId, traceId, judgeModel, rubric,
                mapper.writeValueAsString(safeScores), createdAt);

        Map<String, Object> result = new HashMap<>();
        result.put("evaluationId", evaluationId);
        result.put("runId", runId);
        result.put("traceId", traceId);
        result.put("judgeModel", judgeModel);
        result.put("rubric", rubric);
        result.put("scores", safeScores);
        result.put("createdAt", createdAt);
        return result;
    }

    private JsonNode sanitizeScores(JsonNode value) {
        if (value == null) return mapper.createObjectNode();
        if (value.isObject()) {
            ObjectNode safe = mapper.createObjectNode();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase();
                if (isInternalReasoningKey(key)) continue;
                JsonNode child = field.getValue();
                if (child != null && child.isObject()) {
                    String channel = child.path("channel").asText(
                            child.path("type").asText(child.path("kind").asText("")));
                    String role = child.path("role").asText("");
                    if (isInternalReasoningChannel(channel) && (role.isBlank() || role.equalsIgnoreCase("assistant"))) {
                        continue;
                    }
                }
                safe.set(field.getKey(), sanitizeScores(child));
            }
            return safe;
        }
        if (value.isArray()) {
            ArrayNode safe = mapper.createArrayNode();
            for (JsonNode item : value) {
                if (item != null && item.isObject()) {
                    String channel = item.path("channel").asText(
                            item.path("type").asText(item.path("kind").asText("")));
                    String role = item.path("role").asText("");
                    if (isInternalReasoningChannel(channel) && (role.isBlank() || role.equalsIgnoreCase("assistant"))) {
                        continue;
                    }
                }
                safe.add(sanitizeScores(item));
            }
            return safe;
        }
        return value.deepCopy();
    }

    private boolean isInternalReasoningKey(String key) {
        return key.startsWith("analysis")
                || key.startsWith("reasoning")
                || key.startsWith("chain_of_thought")
                || key.startsWith("chainofthought")
                || key.startsWith("internal_thought")
                || key.startsWith("thought")
                || key.startsWith("scratchpad");
    }

    private boolean isInternalReasoningChannel(String channel) {
        String normalized = channel == null ? "" : channel.toLowerCase();
        return normalized.contains("analysis")
                || normalized.contains("reasoning")
                || normalized.contains("thought")
                || normalized.contains("internal");
    }

    public List<Map<String, Object>> history(String agentId, String traceId) {
        return jdbc.query("""
                SELECT evaluation_id, run_id, trace_id, judge_model, rubric, scores_json, created_at
                  FROM agent_run_evaluation
                 WHERE agent_id = ? AND trace_id = ?
                 ORDER BY created_at DESC
                 LIMIT 50
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("evaluationId", rs.getString("evaluation_id"));
            item.put("runId", rs.getString("run_id"));
            item.put("traceId", rs.getString("trace_id"));
            item.put("judgeModel", rs.getString("judge_model"));
            item.put("rubric", rs.getString("rubric"));
            try {
                item.put("scores", mapper.readTree(rs.getString("scores_json")));
            } catch (Exception e) {
                item.put("scores", mapper.createObjectNode());
            }
            Timestamp createdAt = rs.getTimestamp("created_at");
            item.put("createdAt", createdAt == null ? null : createdAt.toInstant().toString());
            return item;
        }, agentId, traceId);
    }
}
