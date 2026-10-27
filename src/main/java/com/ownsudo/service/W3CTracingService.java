package com.ownsudo.service;

import com.ownsudo.entity.NodeExecution;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class W3CTracingService {

    public Map<String, Object> buildTraceSpans(String instanceId, List<NodeExecution> steps) {
        String traceId = sha256(instanceId).substring(0, 32);
        long currentStartMs = 0;
        List<Map<String, Object>> spans = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            NodeExecution step = steps.get(i);
            String spanId = sha256(step.getId()).substring(0, 16);
            String parentSpanId = (i == 0) ? "0000000000000000" : sha256(steps.get(i - 1).getId()).substring(0, 16);
            long duration = (step.getLatencyMs() != null && step.getLatencyMs() > 0) ? step.getLatencyMs() : 180;

            Map<String, Object> span = new LinkedHashMap<>();
            span.put("traceId", traceId);
            span.put("spanId", spanId);
            span.put("parentSpanId", parentSpanId);
            span.put("name", step.getNodeId() + " [" + step.getNodeType() + "]");
            span.put("startOffsetMs", currentStartMs);
            span.put("durationMs", duration);
            span.put("status", "SUCCESS".equalsIgnoreCase(step.getStatus()) ? "OK" : step.getStatus());
            span.put("tags", Map.of(
                "w3c.traceparent", "00-" + traceId + "-" + spanId + "-01",
                "agent.node_type", step.getNodeType() != null ? step.getNodeType() : "UNKNOWN",
                "agent.tokens", step.getCostTokens() != null ? step.getCostTokens() : 0
            ));

            spans.add(span);
            currentStartMs += duration;
        }

        return Map.of(
            "traceId", traceId,
            "totalDurationMs", currentStartMs,
            "spans", spans
        );
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}