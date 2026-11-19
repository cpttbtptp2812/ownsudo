package com.ownsudo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownsudo.security.AgentApiKeyFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@RestController
@RequestMapping("/api/v1/evaluations/promptfoo")
public class PromptfooReportController {
    private static final int MAX_REPORT_BYTES = 20 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PromptfooReportController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importReport(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            if (request == null) return badRequest("EMPTY_REPORT", "Request body is required.");
            String fileName = safeFileName(String.valueOf(request.getOrDefault("fileName", "promptfoo-report.json")));
            String raw = String.valueOf(request.getOrDefault("content", ""));
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) return badRequest("EMPTY_REPORT", "Report content is empty.");
            if (bytes.length > MAX_REPORT_BYTES) return badRequest("REPORT_TOO_LARGE", "Report exceeds the 20 MB limit.");

            String format = String.valueOf(request.getOrDefault("format", fileName.toLowerCase(Locale.ROOT).endsWith(".jsonl") ? "jsonl" : "json"))
                    .toLowerCase(Locale.ROOT);
            if (!format.equals("json") && !format.equals("jsonl")) {
                return badRequest("UNSUPPORTED_FORMAT", "Only JSON and JSONL reports are supported.");
            }

            ArrayNode rows = mapper.createArrayNode();
            JsonNode root;
            if ("jsonl".equals(format)) {
                for (String line : raw.split("\\R")) {
                    if (line.isBlank()) continue;
                    rows.add(sanitize(mapper.readTree(line)));
                }
                if (rows.isEmpty()) return badRequest("EMPTY_REPORT", "JSONL report contains no rows.");
                root = mapper.createObjectNode().set("results", rows);
            } else {
                JsonNode parsed = mapper.readTree(raw);
                if (parsed == null || parsed.isNull()) return badRequest("INVALID_REPORT", "Report JSON is empty.");
                root = sanitize(parsed);
                JsonNode resultNode = root.path("results");
                if (resultNode.isArray()) resultNode.forEach(rows::add);
                else if (root.isArray()) root.forEach(rows::add);
            }

            int successes = 0, failures = 0, errors = 0;
            for (JsonNode row : rows) {
                if (row.path("error").isTextual() && !row.path("error").asText().isBlank()) errors++;
                else if (row.path("success").asBoolean(false)) successes++;
                else if (row.has("success") && !row.path("success").asBoolean()) failures++;
            }
            if (rows.isEmpty() && !root.has("stats")) {
                return badRequest("INVALID_REPORT", "No Promptfoo results[] or stats object was found.");
            }

            ObjectNode stored = mapper.createObjectNode();
            stored.put("format", format);
            stored.set("report", root);
            String reportJson = mapper.writeValueAsString(stored);
            String reportId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO agent_promptfoo_report
                        (report_id, agent_id, file_name, report_format, result_count,
                         success_count, failure_count, error_count, report_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, reportId, agentId, fileName, format, rows.size(),
                    successes, failures, errors, reportJson, now);
            return ResponseEntity.status(201).body(Map.of(
                    "reportId", reportId, "fileName", fileName, "format", format,
                    "resultCount", rows.size(), "successCount", successes,
                    "failureCount", failures, "errorCount", errors, "createdAt", now.toString()));
        } catch (Exception error) {
            return badRequest("INVALID_REPORT", error.getMessage() == null ? "Could not parse Promptfoo report." : error.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId) {
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT report_id AS reportId, file_name AS fileName, report_format AS format,
                       result_count AS resultCount, success_count AS successCount,
                       failure_count AS failureCount, error_count AS errorCount, created_at AS createdAt
                  FROM agent_promptfoo_report
                 WHERE agent_id = ?
                 ORDER BY created_at DESC LIMIT 100
                """, agentId));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<?> detail(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String reportId) {
        List<Map<String, Object>> reports = jdbc.queryForList("""
                SELECT report_id AS reportId, file_name AS fileName, report_format AS format,
                       result_count AS resultCount, success_count AS successCount,
                       failure_count AS failureCount, error_count AS errorCount,
                       report_json AS reportJson, created_at AS createdAt
                  FROM agent_promptfoo_report
                 WHERE agent_id = ? AND report_id = ?
                """, agentId, reportId);
        if (reports.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reports.get(0));
    }

    private JsonNode sanitize(JsonNode node) {
        if (node == null || node.isValueNode()) return node == null ? mapper.getNodeFactory().nullNode() : node.deepCopy();
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(sanitize(value)));
            return result;
        }
        ObjectNode result = mapper.createObjectNode();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.matches("analysis|reasoning|chain_of_thought|chainofthought|internal_thought|thoughts?|scratchpad")) return;
            result.set(entry.getKey(), sanitize(entry.getValue()));
        });
        return result;
    }

    private static String safeFileName(String name) {
        String value = name == null ? "promptfoo-report.json" : name.replaceAll("[\\\\/\\r\\n\\p{Cntrl}]", "_");
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private ResponseEntity<?> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
