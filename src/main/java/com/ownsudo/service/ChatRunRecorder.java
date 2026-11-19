package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatRunRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ThreadLocal<RunContext> active = new ThreadLocal<>();

    public ChatRunRecorder(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> start(String sessionId, String agentId, String name,
                                     String model, String provider, String userPrompt) {
        String runId = "chat-run-" + UUID.randomUUID();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RunContext context = new RunContext(runId, traceId, sessionId, agentId, now);
        active.set(context);
        jdbc.update("""
                INSERT INTO chat_agent_run
                    (run_id, trace_id, session_id, agent_id, name, model, provider, status,
                     started_at, created_at, updated_at, usage_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, 'MISSING')
                """, runId, traceId, sessionId, blankToNull(agentId),
                blankToDefault(name, "内置 Agent 对话"), blankToNull(model), blankToNull(provider),
                now, now, now);
        recordEvent("RUN_STARTED", "RUNNING", Map.of(
                "source", "BUILT_IN_CHAT",
                "sessionId", sessionId,
                "agentId", blankToNull(agentId) == null ? "" : agentId,
                "model", model == null ? "" : model,
                "provider", provider == null ? "" : provider));
        recordEvent("USER_MESSAGE", "COMPLETED", Map.of("content", userPrompt == null ? "" : userPrompt));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("traceId", traceId);
        result.put("sessionId", sessionId);
        result.put("source", "BUILT_IN_CHAT");
        result.put("status", "RUNNING");
        result.put("startedAt", now.toString());
        return result;
    }

    public void recordStep(String stepId, int sequence, String phase, String status,
                           String title, String subtitle, Map<String, Object> details) {
        RunContext ctx = active.get();
        if (ctx == null) return;
        long timestamp = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", stepId);
        payload.put("sequence", sequence);
        payload.put("phase", phase);
        payload.put("status", status);
        payload.put("title", title);
        payload.put("subtitle", subtitle);
        payload.put("details", details == null ? Map.of() : details);
        payload.put("timestamp", timestamp);
        recordEvent("STEP_" + normalizeEventStatus(status), status, payload);
        if (stepId != null && !stepId.isBlank()) {
            String spanId = safeSpanId(stepId);
            LocalDateTime now = LocalDateTime.ofEpochSecond(timestamp / 1000, (int) (timestamp % 1000) * 1_000_000, ZoneOffset.UTC);
            LocalDateTime started = ctx.spanStarts.computeIfAbsent(spanId, ignored -> now);
            String spanStatus = "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)
                    ? "ERROR" : ("RUNNING".equalsIgnoreCase(status) || "STREAMING".equalsIgnoreCase(status) ? "UNSET" : "OK");
            LocalDateTime ended = "UNSET".equals(spanStatus) ? null : now;
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("source", "BUILT_IN_CHAT");
            attributes.put("phase", phase == null ? "unknown" : phase);
            attributes.put("title", title == null ? "" : title);
            attributes.put("subtitle", subtitle == null ? "" : subtitle);
            attributes.put("details", details == null ? Map.of() : details);
            upsertSpan(ctx, spanId, stepId, phase, spanStatus, started, ended, attributes);
        }
    }

    public void recordToolStart(String toolCallId, String toolName, Map<String, Object> arguments) {
        recordStep(toolCallId, 50, "skill", "RUNNING", "工具调用：" + toolName,
                "模型已发起真实工具调用", Map.of("toolCallId", toolCallId, "tool", toolName,
                        "arguments", arguments == null ? Map.of() : arguments));
    }

    public void recordToolResult(String toolCallId, String toolName, boolean success,
                                 long durationMs, Object result, String error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("toolCallId", toolCallId);
        details.put("tool", toolName);
        details.put("durationMs", durationMs);
        details.put("success", success);
        if (result != null) details.put("result", result);
        if (error != null && !error.isBlank()) details.put("error", error);
        recordStep(toolCallId, 50, "skill", success ? "DONE" : "FAILED",
                "工具结果：" + toolName, success ? "工具执行完成" : "工具执行失败", details);
    }

    public void complete(Integer inputTokens, Integer outputTokens, boolean providerUsage, String outcome) {
        RunContext ctx = active.get();
        if (ctx == null) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String status = "ABORTED".equalsIgnoreCase(outcome) ? "UNKNOWN" : "COMPLETED";
        jdbc.update("""
                UPDATE chat_agent_run
                   SET status = ?, ended_at = ?, duration_ms = ?, input_tokens = ?,
                       output_tokens = ?, total_tokens = ?, usage_source = ?, updated_at = ?
                 WHERE run_id = ?
                """, status, now, Duration.between(ctx.startedAt, now).toMillis(),
                providerUsage ? inputTokens : null, providerUsage ? outputTokens : null,
                providerUsage && inputTokens != null && outputTokens != null ? inputTokens + outputTokens : null,
                providerUsage ? "PROVIDER" : "MISSING", now, ctx.runId);
        recordEvent("RUN_" + status, status, Map.of(
                "durationMs", Duration.between(ctx.startedAt, now).toMillis(),
                "usageSource", providerUsage ? "PROVIDER" : "MISSING",
                "inputTokens", providerUsage && inputTokens != null ? inputTokens : -1,
                "outputTokens", providerUsage && outputTokens != null ? outputTokens : -1));
    }

    public void fail(Throwable error) {
        RunContext ctx = active.get();
        if (ctx == null) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE chat_agent_run SET status = 'ERROR', ended_at = ?, duration_ms = ?,
                    error_message = ?, updated_at = ? WHERE run_id = ?
                """, now, Duration.between(ctx.startedAt, now).toMillis(),
                error == null ? "Unknown execution error" : safeError(error), now, ctx.runId);
        recordEvent("RUN_FAILED", "ERROR", Map.of(
                "errorType", error == null ? "Unknown" : error.getClass().getSimpleName(),
                "message", error == null ? "Unknown execution error" : safeError(error)));
    }

    public void clear() { active.remove(); }

    public Map<String, Object> currentIdentity() {
        RunContext ctx = active.get();
        if (ctx == null) return Map.of();
        return Map.of("runId", ctx.runId, "traceId", ctx.traceId, "sessionId", ctx.sessionId);
    }

    private void recordEvent(String type, String status, Object payload) {
        RunContext ctx = active.get();
        if (ctx == null) return;
        try {
            long seq = ctx.sequence.incrementAndGet();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            JsonNode safe = sanitize(mapper.valueToTree(payload));
            jdbc.update("""
                    INSERT INTO chat_agent_run_event
                        (event_id, run_id, sequence_no, event_type, status, event_time, payload_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), ctx.runId, seq, type, status, now,
                    mapper.writeValueAsString(safe));
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist built-in Agent event", e);
        }
    }

    private void upsertSpan(RunContext ctx, String spanId, String name, String kind, String status,
                            LocalDateTime started, LocalDateTime ended, Map<String, Object> attributes) {
        try {
            String json = mapper.writeValueAsString(sanitize(mapper.valueToTree(attributes)));
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_run_span WHERE run_id = ? AND span_id = ?",
                    Integer.class, ctx.runId, spanId);
            Long duration = ended == null ? null : Duration.between(started, ended).toMillis();
            if (count == 0) {
                jdbc.update("""
                        INSERT INTO chat_agent_run_span
                            (run_id, span_id, parent_span_id, name, span_kind, status,
                             started_at, ended_at, duration_ms, attributes_json)
                        VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                        """, ctx.runId, spanId, blankToDefault(name, "Agent step"),
                        normalizeKind(kind), status, started, ended, duration, json);
            } else {
                jdbc.update("""
                        UPDATE chat_agent_run_span SET name = ?, span_kind = ?, status = ?,
                            started_at = ?, ended_at = ?, duration_ms = ?, attributes_json = ?
                         WHERE run_id = ? AND span_id = ?
                        """, blankToDefault(name, "Agent step"), normalizeKind(kind), status,
                        started, ended, duration, json, ctx.runId, spanId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist built-in Agent span", e);
        }
    }

    private JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull()) return mapper.nullNode();
        if (value.isObject()) {
            ObjectNode safe = mapper.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (isInternalKey(key)) return;
                JsonNode child = entry.getValue();
                if (child != null && child.isObject()) {
                    String channel = child.path("channel").asText(child.path("type").asText(""));
                    String role = child.path("role").asText("");
                    if ((role.isBlank() || "assistant".equalsIgnoreCase(role)) && isInternalChannel(channel)) return;
                }
                safe.set(entry.getKey(), sanitize(child));
            });
            return safe;
        }
        if (value.isArray()) {
            ArrayNode safe = mapper.createArrayNode();
            for (JsonNode item : value) {
                if (item != null && item.isObject()) {
                    String channel = item.path("channel").asText(item.path("type").asText(""));
                    String role = item.path("role").asText("");
                    if ((role.isBlank() || "assistant".equalsIgnoreCase(role)) && isInternalChannel(channel)) continue;
                }
                safe.add(sanitize(item));
            }
            return safe;
        }
        return value.deepCopy();
    }

    private boolean isInternalKey(String key) {
        return key.startsWith("reasoning") || key.startsWith("analysis")
                || key.startsWith("chain_of_thought") || key.startsWith("internal_thought")
                || key.startsWith("scratchpad") || key.equals("thoughts");
    }
    private boolean isInternalChannel(String channel) {
        String c = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        return c.contains("analysis") || c.contains("reasoning") || c.contains("thought") || c.contains("internal");
    }
    private String normalizeEventStatus(String status) {
        if (status == null) return "UNKNOWN";
        return status.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
    private String normalizeKind(String kind) {
        if (kind == null) return "INTERNAL";
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "llm", "model" -> "CLIENT";
            case "skill", "tool" -> "INTERNAL";
            case "context", "workflow", "intent", "delivery" -> "INTERNAL";
            default -> "INTERNAL";
        };
    }
    private String safeSpanId(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9_-]", "-");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
    private String safeError(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String blankToDefault(String value, String fallback) { return blankToNull(value) == null ? fallback : value; }

    private static final class RunContext {
        final String runId, traceId, sessionId, agentId;
        final LocalDateTime startedAt;
        final AtomicInteger sequence = new AtomicInteger();
        final Map<String, LocalDateTime> spanStarts = new ConcurrentHashMap<>();
        RunContext(String runId, String traceId, String sessionId, String agentId, LocalDateTime startedAt) {
            this.runId = runId; this.traceId = traceId; this.sessionId = sessionId;
            this.agentId = agentId; this.startedAt = startedAt;
        }
    }
}
