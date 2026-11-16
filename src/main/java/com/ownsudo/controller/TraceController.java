package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.IngestTraceSpanRequest;
import com.ownsudo.dto.TraceSpanResponse;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.service.TraceEventStreamService;
import com.ownsudo.service.TraceSpanService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/traces")
public class TraceController {
    private final TraceSpanService service;
    private final TraceEventStreamService eventStream;
    private final ObjectMapper mapper;

    public TraceController(TraceSpanService service, TraceEventStreamService eventStream, ObjectMapper mapper) {
        this.service = service;
        this.eventStream = eventStream;
        this.mapper = mapper;
    }

    @PostMapping("/spans")
    public ResponseEntity<?> ingest(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) IngestTraceSpanRequest request) {
        try {
            TraceSpanResponse span = TraceSpanResponse.from(service.ingest(agentId, request), mapper);
            // Publish only after TraceSpanService's transactional call has returned successfully.
            eventStream.publish(agentId, span);
            return ResponseEntity.status(202).body(span);
        } catch (TraceSpanService.SpanConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", Map.of("code", "TRACE_SPAN_CONFLICT", "message", e.getMessage())));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "INVALID_TRACE_SPAN", "message", e.getMessage())));
        }
    }

    @GetMapping
    public ResponseEntity<List<TraceSpanResponse>> recent(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId) {
        return ResponseEntity.ok(service.recentSpans(agentId).stream()
                .map(span -> TraceSpanResponse.from(span, mapper)).toList());
    }

    @GetMapping("/{traceId}/spans")
    public ResponseEntity<List<TraceSpanResponse>> list(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String traceId) {
        return ResponseEntity.ok(service.list(agentId, traceId).stream()
                .map(span -> TraceSpanResponse.from(span, mapper)).toList());
    }

    @GetMapping(path = "/{traceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> events(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String traceId) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(eventStream.subscribe(agentId, traceId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", Map.of("code", "TRACE_STREAM_LIMIT", "message", e.getMessage())));
        }
    }
}
