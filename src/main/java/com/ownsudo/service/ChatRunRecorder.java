package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatRunRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @Value("${ownagent.trace.capture-content:false}")
    private String captureContentSetting = "false";
    private final ThreadLocal<RunContext> active = new ThreadLocal<>();

    public ChatRunRecorder(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> start(String sessionId, String agentId, String name,
                                     String model, String provider, String userPrompt) {
        String runId = "chat-run-" + UUID.randomUUID();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
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
        Map<String, Object> userMessage = new LinkedHashMap<>();
        boolean captureContent = isContentCaptureEnabled();
        userMessage.put("captured", captureContent);
        if (captureContent) userMessage.put("content", userPrompt == null ? "" : userPrompt);
        recordEvent("USER_MESSAGE", "COMPLETED", userMessage);
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
            captureProviderUsage(ctx, spanId, details);
            if ("llm".equalsIgnoreCase(phase) && "RUNNING".equalsIgnoreCase(status)) {
                ctx.lastModelSpanId = spanId;
            }
            String parentSpanId = "skill".equalsIgnoreCase(phase) ? ctx.lastModelSpanId : null;
            LocalDateTime now = LocalDateTime.now();
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
            if (details != null) {
                Object model = details.get("actual_model") != null ? details.get("actual_model") : details.get("model");
                if (model != null) attributes.put("model", model);
                Object provider = details.get("provider");
                if (provider != null) attributes.put("provider", provider);
                Object tool = details.get("tool");
                if (tool != null) attributes.put("gen_ai.tool.name", tool);
                for (String key : List.of("prompt_messages", "model_response", "tool_schema", "tool_arguments", "tool_result", "usage", "error_message", "content_capture")) {
                    if (details.containsKey(key)) attributes.put(key, details.get(key));
                }
                if (details.containsKey("arguments")) attributes.put("tool_arguments", details.get("arguments"));
                if (details.containsKey("result")) attributes.put("tool_result", details.get("result"));
                if (details.containsKey("error")) attributes.put("error_message", details.get("error"));
            }
            upsertSpan(ctx, spanId, parentSpanId, stepId, phase, spanStatus, started, ended, attributes);
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
        LocalDateTime now = LocalDateTime.now();
        String status = "ABORTED".equalsIgnoreCase(outcome) ? "UNKNOWN" : "COMPLETED";
        if (!ctx.providerUsageSeen && providerUsage) {
            ctx.inputTokens += inputTokens == null ? 0 : inputTokens;
            ctx.outputTokens += outputTokens == null ? 0 : outputTokens;
            ctx.providerUsageSeen = true;
        }
        boolean hasUsage = ctx.providerUsageSeen;
        jdbc.update("""
                UPDATE chat_agent_run
                   SET status = ?, ended_at = ?, duration_ms = ?, input_tokens = ?,
                       output_tokens = ?, total_tokens = ?, usage_source = ?, updated_at = ?
                 WHERE run_id = ?
                """, status, now, Duration.between(ctx.startedAt, now).toMillis(),
                hasUsage ? ctx.inputTokens : null, hasUsage ? ctx.outputTokens : null,
                hasUsage ? ctx.inputTokens + ctx.outputTokens : null,
                hasUsage ? "PROVIDER" : "MISSING", now, ctx.runId);
        recordEvent("RUN_" + status, status, Map.of(
                "durationMs", Duration.between(ctx.startedAt, now).toMillis(),
                "usageSource", hasUsage ? "PROVIDER" : "MISSING",
                "inputTokens", hasUsage ? ctx.inputTokens : -1,
                "outputTokens", hasUsage ? ctx.outputTokens : -1));
        active.remove();
    }

    public void fail(Throwable error) {
        RunContext ctx = active.get();
        if (ctx == null) return;
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                UPDATE chat_agent_run SET status = 'ERROR', ended_at = ?, duration_ms = ?,
                    error_message = ?, input_tokens = ?, output_tokens = ?, total_tokens = ?,
                    usage_source = ?, updated_at = ? WHERE run_id = ?
                """, now, Duration.between(ctx.startedAt, now).toMillis(),
                error == null ? "Unknown execution error" : safeError(error),
                ctx.providerUsageSeen ? ctx.inputTokens : null,
                ctx.providerUsageSeen ? ctx.outputTokens : null,
                ctx.providerUsageSeen ? ctx.inputTokens + ctx.outputTokens : null,
                ctx.providerUsageSeen ? "PROVIDER" : "MISSING", now, ctx.runId);
        recordEvent("RUN_FAILED", "ERROR", Map.of(
                "errorType", error == null ? "Unknown" : error.getClass().getSimpleName(),
                "message", error == null ? "Unknown execution error" : safeError(error)));
        active.remove();
    }

    private boolean isContentCaptureEnabled() {
        String setting = captureContentSetting == null ? "" : captureContentSetting.trim();
        return "1".equals(setting) || "true".equalsIgnoreCase(setting)
                || "yes".equalsIgnoreCase(setting) || "on".equalsIgnoreCase(setting);
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
            LocalDateTime now = LocalDateTime.now();
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

    private void upsertSpan(RunContext ctx, String spanId, String parentSpanId, String name, String kind, String status,
                            LocalDateTime started, LocalDateTime ended, Map<String, Object> attributes) {
        try {
            String json = mapper.writeValueAsString(sanitize(mapper.valueToTree(attributes)));
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_run_span WHERE run_id = ? AND span_id = ?",
                    Integer.class, ctx.runId, spanId);
            Long duration = ended == null ? null : Duration.between(started, ended).toMillis();
            Long inputTokens = tokenValue(attributes, "prompt_tokens", "input_tokens");
            Long outputTokens = tokenValue(attributes, "completion_tokens", "output_tokens");
            String usageSource = inputTokens != null || outputTokens != null ? "PROVIDER" : "MISSING";
            if (count == 0) {
                jdbc.update("""
                        INSERT INTO chat_agent_run_span
                            (run_id, span_id, parent_span_id, name, span_kind, status,
                             started_at, ended_at, duration_ms, input_tokens, output_tokens, usage_source, attributes_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, ctx.runId, spanId, parentSpanId, blankToDefault(name, "Agent step"),
                        normalizeKind(kind), status, started, ended, duration, inputTokens, outputTokens, usageSource, json);
            } else {
                jdbc.update("""
                        UPDATE chat_agent_run_span SET parent_span_id = ?, name = ?, span_kind = ?, status = ?,
                            started_at = ?, ended_at = ?, duration_ms = ?, input_tokens = ?,
                            output_tokens = ?, usage_source = ?, attributes_json = ?
                         WHERE run_id = ? AND span_id = ?
                        """, parentSpanId, blankToDefault(name, "Agent step"), normalizeKind(kind), status,
                        started, ended, duration, inputTokens, outputTokens, usageSource, json, ctx.runId, spanId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist built-in Agent span", e);
        }
    }

    private void captureProviderUsage(RunContext ctx, String spanId, Map<String, Object> details) {
        if (details == null || ctx.usageSpanIds.contains(spanId)) return;
        Object raw = details.get("usage");
        if (!(raw instanceof Map<?, ?> usage)) return;
        Long input = number(usage.get("prompt_tokens"));
        if (input == null) input = number(usage.get("input_tokens"));
        Long output = number(usage.get("completion_tokens"));
        if (output == null) output = number(usage.get("output_tokens"));
        if (input == null && output == null) return;
        if (ctx.usageSpanIds.add(spanId)) {
            ctx.inputTokens += input == null ? 0 : input;
            ctx.outputTokens += output == null ? 0 : output;
            ctx.providerUsageSeen = true;
        }
    }

    private Long tokenValue(Map<String, Object> attributes, String first, String second) {
        Object details = attributes.get("details");
        if (!(details instanceof Map<?, ?> detailMap)) return null;
        Object raw = detailMap.get("usage");
        if (!(raw instanceof Map<?, ?> usage)) return null;
        Long value = number(usage.get(first));
        return value == null ? number(usage.get(second)) : value;
    }
    private Long number(Object value) {
        if (value instanceof Number n && n.longValue() >= 0) return n.longValue();
        return null;
    }

    private JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull()) return mapper.getNodeFactory().nullNode();
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
        final Set<String> usageSpanIds = ConcurrentHashMap.newKeySet();
        volatile long inputTokens = 0;
        volatile long outputTokens = 0;
        volatile boolean providerUsageSeen = false;
        volatile String lastModelSpanId;
        RunContext(String runId, String traceId, String sessionId, String agentId, LocalDateTime startedAt) {
            this.runId = runId; this.traceId = traceId; this.sessionId = sessionId;
            this.agentId = agentId; this.startedAt = startedAt;
        }
    }
}
