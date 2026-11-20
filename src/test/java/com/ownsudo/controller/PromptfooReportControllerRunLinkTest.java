package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromptfooReportControllerRunLinkTest {
    @Test
    void rejectsRunIdsThatDoNotBelongToTheAuthenticatedAgent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        PromptfooReportController controller = new PromptfooReportController(jdbc, new ObjectMapper());

        var response = controller.importReport("agent-a", Map.of(
                "fileName", "results.json",
                "format", "json",
                "content", "{\"results\":[{\"success\":true}]}",
                "runId", "run-owned-by-another-agent"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
