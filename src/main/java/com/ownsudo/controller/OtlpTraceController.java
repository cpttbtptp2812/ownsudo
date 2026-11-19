package com.ownsudo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import com.ownsudo.dto.IngestTraceSpanRequest;
import com.ownsudo.dto.TraceSpanResponse;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.service.TraceEventStreamService;
import com.ownsudo.service.TraceSpanService;
import com.ownsudo.service.TraceExportService;
import com.ownsudo.service.AgentRunPersistenceService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * OTLP/HTTP JSON receiver. It accepts standard OpenTelemetry ExportTraceServiceRequest
 * payloads and stores spans under the authenticated OwnAgent identity.
 */
@RestController
@RequestMapping("/api/v1/otlp")
public class OtlpTraceController {
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);
    private final TraceSpanService spans;
    private final TraceEventStreamService stream;
    private final TraceExportService exporter;
    private final ObjectMapper mapper;
    private final AgentRunPersistenceService runHistory;

    public OtlpTraceController(TraceSpanService spans, TraceEventStreamService stream,
                               TraceExportService exporter, ObjectMapper mapper, AgentRunPersistenceService runHistory) {
        this.spans = spans;
        this.stream = stream;
        this.exporter = exporter;
        this.mapper = mapper;
        this.runHistory = runHistory;
    }

    @PostMapping(path = "/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) JsonNode request) {
        return ingestPayload(agentId, request);
    }

    /**
     * OTLP/HTTP protobuf is the default transport for many OpenTelemetry SDKs.
     * Convert the generated wire message through the official protobuf JSON mapping,
     * then reuse the exact same validation, normalization and persistence path as JSON.
     */
    @PostMapping(path = "/v1/traces", consumes = "application/x-protobuf")
    public ResponseEntity<?> ingestProtobuf(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) byte[] payload) {
        if (payload == null || payload.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                    "code", "INVALID_OTLP_REQUEST", "message", "Protobuf request body is empty")));
        }
        try {
            ExportTraceServiceRequest message = ExportTraceServiceRequest.parseFrom(payload);
            JsonNode request = mapper.readTree(JsonFormat.printer().print(message));
            return ingestPayload(agentId, request);
        } catch (InvalidProtocolBufferException error) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                    "code", "INVALID_OTLP_PROTOBUF", "message", "Could not decode OTLP ExportTraceServiceRequest")));
        } catch (Exception error) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                    "code", "INVALID_OTLP_REQUEST", "message", error.getMessage() == null ? "Could not decode OTLP request" : error.getMessage())));
        }
    }

    private ResponseEntity<?> ingestPayload(String agentId, JsonNode request) {
        if (request == null || !request.isObject() || !request.path("resourceSpans").isArray()) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                    "code", "INVALID_OTLP_REQUEST", "message", "Expected OTLP ExportTraceServiceRequest.resourceSpans[]")));
        }

        int accepted = 0;
        int rejected = 0;
        String firstError = null;
        for (JsonNode resourceSpans : request.path("resourceSpans")) {
            ObjectNode resourceAttributes = decodeAttributes(resourceSpans.path("resource").path("attributes"));
            JsonNode scopes = resourceSpans.path("scopeSpans").isArray()
                    ? resourceSpans.path("scopeSpans") : resourceSpans.path("instrumentationLibrarySpans");
            if (!scopes.isArray()) continue;
            for (JsonNode scope : scopes) {
                JsonNode spanList = scope.path("spans");
                if (!spanList.isArray()) continue;
                for (JsonNode raw : spanList) {
                    try {
                        var persisted = spans.ingest(agentId, toRequest(raw, resourceAttributes));
                        runHistory.recordSpan(agentId, persisted);
                        TraceSpanResponse saved = TraceSpanResponse.from(persisted, mapper);
                        stream.publish(agentId, saved);
                        accepted++;
                    } catch (Exception error) {
                        rejected++;
                        if (firstError == null) firstError = error.getMessage();
                    }
                }
            }
        }

        if (accepted > 0 && rejected == 0) exporter.forward(request);
        return ResponseEntity.accepted().body(Map.of(
                "acceptedSpans", accepted,
                "rejectedSpans", rejected,
                "partialSuccess", Map.of(
                        "rejectedSpans", rejected,
                        "errorMessage", firstError == null ? "" : firstError)));
    }

    private IngestTraceSpanRequest toRequest(JsonNode span, ObjectNode resourceAttributes) {
        String traceId = text(span, "traceId");
        String spanId = text(span, "spanId");
        String name = text(span, "name");
        Instant start = fromUnixNanos(text(span, "startTimeUnixNano"));
        Instant end = fromUnixNanos(text(span, "endTimeUnixNano"));
        ObjectNode attributes = resourceAttributes.deepCopy();
        ObjectNode spanAttributes = decodeAttributes(span.path("attributes"));
        spanAttributes.fields().forEachRemaining(entry -> attributes.set(entry.getKey(), entry.getValue()));

        Long input = number(attributes, "gen_ai.usage.input_tokens", "llm.token_count.prompt");
        Long output = number(attributes, "gen_ai.usage.output_tokens", "llm.token_count.completion");
        Long total = number(attributes, "gen_ai.usage.total_tokens");
        String rawStatus = text(span.path("status"), "code").toUpperCase();
        String status = switch (rawStatus) {
            case "1", "STATUS_CODE_OK" -> "OK";
            case "2", "STATUS_CODE_ERROR" -> "ERROR";
            default -> "UNSET";
        };
        String rawKind = text(span, "kind").toUpperCase();
        String kind = switch (rawKind) {
            case "1", "SPAN_KIND_INTERNAL" -> "INTERNAL";
            case "2", "SPAN_KIND_SERVER" -> "SERVER";
            case "3", "SPAN_KIND_CLIENT" -> "CLIENT";
            case "4", "SPAN_KIND_PRODUCER" -> "PRODUCER";
            case "5", "SPAN_KIND_CONSUMER" -> "CONSUMER";
            default -> "INTERNAL";
        };
        String runId = firstText(attributes, "ownagent.run.id");
        String usageSource = input != null || output != null || total != null ? "PROVIDER" : "MISSING";
        return new IngestTraceSpanRequest(traceId, spanId, blankToNull(text(span, "parentSpanId")),
                runId, name, kind, status, start, end, input, output, total, usageSource, attributes);
    }

    private ObjectNode decodeAttributes(JsonNode list) {
        ObjectNode result = mapper.createObjectNode();
        if (!list.isArray()) return result;
        for (JsonNode item : list) {
            String key = text(item, "key");
            if (!key.isBlank() && item.has("value")) result.set(key, decodeValue(item.path("value")));
        }
        return result;
    }

    private JsonNode decodeValue(JsonNode value) {
        if (value.has("stringValue")) return mapper.valueToTree(value.path("stringValue").asText());
        if (value.has("intValue")) {
            try { return mapper.valueToTree(Long.parseLong(value.path("intValue").asText())); }
            catch (NumberFormatException ignored) { return mapper.valueToTree(value.path("intValue").asText()); }
        }
        if (value.has("doubleValue")) return mapper.valueToTree(value.path("doubleValue").asDouble());
        if (value.has("boolValue")) return mapper.valueToTree(value.path("boolValue").asBoolean());
        if (value.has("arrayValue")) {
            var array = mapper.createArrayNode();
            value.path("arrayValue").path("values").forEach(item -> array.add(decodeValue(item)));
            return array;
        }
        if (value.has("kvlistValue")) {
            ObjectNode object = mapper.createObjectNode();
            JsonNode values = value.path("kvlistValue").path("values");
            if (values.isArray()) for (JsonNode item : values) {
                String key = text(item, "key");
                if (!key.isBlank()) object.set(key, decodeValue(item.path("value")));
            }
            return object;
        }
        if (value.has("bytesValue")) return mapper.valueToTree(value.path("bytesValue").asText());
        return value.deepCopy();
    }

    private static Long number(ObjectNode attributes, String... keys) {
        for (String key : keys) {
            JsonNode value = attributes.get(key);
            if (value == null || value.isNull()) continue;
            try { return value.isNumber() ? value.longValue() : Long.parseLong(value.asText()); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static String firstText(ObjectNode attributes, String... keys) {
        for (String key : keys) {
            JsonNode value = attributes.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static Instant fromUnixNanos(String value) {
        if (value == null || value.isBlank()) return null;
        BigInteger nanos = new BigInteger(value);
        BigInteger[] parts = nanos.divideAndRemainder(BILLION);
        return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValue());
    }
}
