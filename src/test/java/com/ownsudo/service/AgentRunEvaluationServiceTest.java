package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRunEvaluationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRunEvaluationService service = new AgentRunEvaluationService(jdbc, mapper);

    @Test
    void savesJudgeResultAgainstTheOwningAgentRun() throws Exception {
        doReturn(List.of(Map.of("run_id", "run-1")))
                .when(jdbc).queryForList(anyString(), any(Object[].class));
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));

        var scores = mapper.readTree("""
                {"overall": 0.9, "taskCompletion": 1.0, "summary": "Trace evidence is sufficient"}
                """);
        Map<String, Object> saved = service.save(
                "agent-1", "trace-1", "judge-model", "task completion", scores);

        assertEquals("run-1", saved.get("runId"));
        assertEquals("trace-1", saved.get("traceId"));
        assertEquals(scores, saved.get("scores"));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INSERT INTO agent_run_evaluation"),
                any(Object[].class));
    }
}
