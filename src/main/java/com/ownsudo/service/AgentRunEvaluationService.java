package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
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
        jdbc.update("""
                INSERT INTO agent_run_evaluation
                    (evaluation_id, agent_id, run_id, trace_id, judge_model, rubric, scores_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, evaluationId, agentId, runId, traceId, judgeModel, rubric,
                mapper.writeValueAsString(scores), createdAt);

        Map<String, Object> result = new HashMap<>();
        result.put("evaluationId", evaluationId);
        result.put("runId", runId);
        result.put("traceId", traceId);
        result.put("judgeModel", judgeModel);
        result.put("rubric", rubric);
        result.put("scores", scores);
        result.put("createdAt", createdAt);
        return result;
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
