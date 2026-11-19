package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.TraceSpanEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentRunPersistenceServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentRunPersistenceService service = new AgentRunPersistenceService(jdbc, new ObjectMapper());

    @Test
    void projectsPersistedSpanIntoRunAndLifecycleEvent() {
        Map<String, Object> aggregate = new HashMap<>();
        aggregate.put("span_count", 1L);
        aggregate.put("error_count", 0L);
        aggregate.put("open_count", 0L);
        aggregate.put("input_tokens", 120L);
        aggregate.put("output_tokens", 30L);
        aggregate.put("total_tokens", 150L);
        aggregate.put("provider_usage_count", 1L);
        aggregate.put("estimated_usage_count", 0L);
        aggregate.put("started_at", Timestamp.valueOf(LocalDateTime.parse("2026-11-19T10:00:00")));
        aggregate.put("ended_at", Timestamp.valueOf(LocalDateTime.parse("2026-11-19T10:00:02")));
        when(jdbc.queryForMap(anyString(), eq("agent-1"), eq("trace-1"))).thenReturn(aggregate);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("agent-1"), eq("run-1"))).thenReturn(0);
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));

        TraceSpanEntity span = new TraceSpanEntity();
        span.setAgentId("agent-1");
        span.setTraceId("trace-1");
        span.setRunId("run-1");
        span.setSpanId("span-1");
        span.setName("llm.chat.completions");
        span.setSpanKind("CLIENT");
        span.setStatus("OK");
        span.setStartedAt(LocalDateTime.parse("2026-11-19T10:00:00"));
        span.setEndedAt(LocalDateTime.parse("2026-11-19T10:00:02"));
        span.setDurationMs(2000L);
        span.setInputTokens(120L);
        span.setOutputTokens(30L);
        span.setTotalTokens(150L);
        span.setUsageSource("PROVIDER");
        span.setAttributesJson("{\"session.id\":\"session-1\"}");

        assertDoesNotThrow(() -> service.recordSpan("agent-1", span));
        verify(jdbc).queryForMap(contains("usage_source = 'PROVIDER'"), eq("agent-1"), eq("trace-1"));
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }
}
