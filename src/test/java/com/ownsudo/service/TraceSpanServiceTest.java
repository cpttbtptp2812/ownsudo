package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.IngestTraceSpanRequest;
import com.ownsudo.entity.TraceSpanEntity;
import com.ownsudo.repository.TraceSpanRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceSpanServiceTest {
    private final TraceSpanRepository repository = mock(TraceSpanRepository.class);
    private final TraceSpanService service = new TraceSpanService(repository, new ObjectMapper());

    @Test void storesProviderUsageAndComputesDuration() {
        when(repository.findByAgentIdAndTraceIdAndSpanId("agent-1", "trace-1", "span-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(TraceSpanEntity.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.ingest("agent-1", request("span-1", "PROVIDER", 100L, 40L));
        assertEquals("agent-1", result.getAgentId());
        assertEquals(140L, result.getTotalTokens());
        assertEquals(1000L, result.getDurationMs());
        assertEquals("PROVIDER", result.getUsageSource());
    }

    @Test void acceptsMissingUsageWithoutInventingTokenCounts() {
        when(repository.findByAgentIdAndTraceIdAndSpanId("agent-1", "trace-1", "span-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(TraceSpanEntity.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.ingest("agent-1", request("span-1", "MISSING", null, null));
        assertNull(result.getInputTokens());
        assertNull(result.getOutputTokens());
        assertNull(result.getTotalTokens());
        assertEquals("MISSING", result.getUsageSource());
    }

    @Test void rejectsProviderUsageWithoutAnyTokenCount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest("agent-1", request("span-1", "PROVIDER", null, null)));
        verifyNoInteractions(repository);
    }

    @Test void rejectsNegativeTokenCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest("agent-1", request("span-1", "PROVIDER", -1L, 2L)));
    }

    @Test void rejectsEndBeforeStart() {
        var request = request("span-1", "MISSING", null, null);
        request = new IngestTraceSpanRequest(request.traceId(), request.spanId(), null, null,
                request.name(), "CLIENT", "OK", Instant.parse("2026-11-14T10:00:01Z"),
                Instant.parse("2026-11-14T10:00:00Z"), null, null, null, "MISSING", null);
        var invalid = request;
        assertThrows(IllegalArgumentException.class, () -> service.ingest("agent-1", invalid));
    }

    @Test void exactDuplicateIsIdempotentAndConflictingDuplicateIsRejected() {
        var stored = new TraceSpanEntity();
        stored.setName("llm.chat");
        stored.setParentSpanId(null);
        stored.setRunId("run-1");
        stored.setSpanKind("CLIENT");
        stored.setStatus("OK");
        stored.setStartedAt(java.time.LocalDateTime.ofInstant(Instant.parse("2026-11-14T10:00:00Z"), java.time.ZoneOffset.UTC));
        stored.setEndedAt(java.time.LocalDateTime.ofInstant(Instant.parse("2026-11-14T10:00:01Z"), java.time.ZoneOffset.UTC));
        stored.setInputTokens(100L); stored.setOutputTokens(40L); stored.setTotalTokens(140L);
        stored.setUsageSource("PROVIDER"); stored.setAttributesJson(null);
        when(repository.findByAgentIdAndTraceIdAndSpanId("agent-1", "trace-1", "span-1"))
                .thenReturn(Optional.of(stored));
        assertSame(stored, service.ingest("agent-1", request("span-1", "PROVIDER", 100L, 40L)));
        assertThrows(TraceSpanService.SpanConflictException.class,
                () -> service.ingest("agent-1", request("span-1", "PROVIDER", 200L, 40L)));
        verify(repository, never()).save(any());
    }

    @Test void allowsAnOpenSpanToBeFinalizedOnce() {
        var started = new IngestTraceSpanRequest("trace-1", "span-live", null, "run-1",
                "llm.chat", "CLIENT", "UNSET",
                Instant.parse("2026-11-14T10:00:00Z"), null,
                null, null, null, "MISSING", null);
        var completed = new IngestTraceSpanRequest("trace-1", "span-live", null, "run-1",
                "llm.chat", "CLIENT", "OK",
                Instant.parse("2026-11-14T10:00:00Z"),
                Instant.parse("2026-11-14T10:00:02Z"),
                120L, 30L, 150L, "PROVIDER", null);

        when(repository.findByAgentIdAndTraceIdAndSpanId("agent-1", "trace-1", "span-live"))
                .thenReturn(Optional.empty());
        when(repository.save(any(TraceSpanEntity.class))).thenAnswer(call -> call.getArgument(0));
        var open = service.ingest("agent-1", started);
        assertEquals("UNSET", open.getStatus());
        assertNull(open.getEndedAt());

        when(repository.findByAgentIdAndTraceIdAndSpanId("agent-1", "trace-1", "span-live"))
                .thenReturn(Optional.of(open));
        var finished = service.ingest("agent-1", completed);
        assertEquals("OK", finished.getStatus());
        assertEquals(2000L, finished.getDurationMs());
        assertEquals(150L, finished.getTotalTokens());
        verify(repository, times(2)).save(any(TraceSpanEntity.class));
    }

    private IngestTraceSpanRequest request(String spanId, String usageSource, Long input, Long output) {
        return new IngestTraceSpanRequest("trace-1", spanId, null, "run-1", "llm.chat",
                "CLIENT", "OK", Instant.parse("2026-11-14T10:00:00Z"),
                Instant.parse("2026-11-14T10:00:01Z"), input, output, null, usageSource, null);
    }
}
