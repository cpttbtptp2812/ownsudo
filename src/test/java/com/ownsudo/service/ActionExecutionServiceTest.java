package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionExecutionServiceTest {
    private final ActionExecutionStateService state = mock(ActionExecutionStateService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void executesOnlyAfterRuntimeClaimsActionAndPersistsSuccess() {
        ActionEntity action = readyAction("demo.echo", "{\"message\":\"hello\"}");
        when(state.claim("agent-a", "act-1")).thenReturn(action);
        ActionEntity succeeded = new ActionEntity();
        succeeded.setId("act-1");
        succeeded.setStatus("SUCCEEDED");
        when(state.succeed(eq("agent-a"), eq("act-1"), anyMap())).thenReturn(succeeded);

        ActionToolHandlerRegistry registry = new ActionToolHandlerRegistry(java.util.List.of(new DemoEchoToolHandler()));
        ActionExecutionService service = new ActionExecutionService(state, registry, mapper);
        ActionEntity result = service.execute("agent-a", "act-1");

        assertEquals("SUCCEEDED", result.getStatus());
        verify(state).claim("agent-a", "act-1");
        verify(state).succeed("agent-a", "act-1", Map.of("message", "hello", "characterCount", 5));
        verify(state, never()).fail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void missingHandlerFailsInsteadOfReportingSuccess() {
        when(state.claim("agent-a", "act-2")).thenReturn(readyAction("uninstalled.tool", "{}"));
        ActionEntity failed = new ActionEntity();
        failed.setStatus("FAILED");
        when(state.fail(eq("agent-a"), eq("act-2"), eq("TOOL_HANDLER_NOT_FOUND"), contains("uninstalled.tool")))
                .thenReturn(failed);

        ActionExecutionService service = new ActionExecutionService(state,
                new ActionToolHandlerRegistry(java.util.List.of()), mapper);
        ActionEntity result = service.execute("agent-a", "act-2");

        assertEquals("FAILED", result.getStatus());
        verify(state, never()).succeed(anyString(), anyString(), anyMap());
        verify(state).fail(eq("agent-a"), eq("act-2"), eq("TOOL_HANDLER_NOT_FOUND"), anyString());
    }

    @Test
    void invalidToolArgumentsArePersistedAsFailure() {
        when(state.claim("agent-a", "act-3")).thenReturn(readyAction("text.transform", "{\"text\":\"hello\",\"operation\":\"explode\"}"));
        ActionEntity failed = new ActionEntity();
        failed.setStatus("FAILED");
        when(state.fail(eq("agent-a"), eq("act-3"), eq("INVALID_TOOL_ARGUMENTS"), anyString())).thenReturn(failed);

        ActionExecutionService service = new ActionExecutionService(state,
                new ActionToolHandlerRegistry(java.util.List.of(new TextTransformToolHandler())), mapper);
        ActionEntity result = service.execute("agent-a", "act-3");

        assertEquals("FAILED", result.getStatus());
        verify(state, never()).succeed(anyString(), anyString(), anyMap());
    }

    private ActionEntity readyAction(String tool, String arguments) {
        ActionEntity action = new ActionEntity();
        action.setId("act-1");
        action.setAgentId("agent-a");
        action.setToolName(tool);
        action.setArgumentsJson(arguments);
        action.setDecision("ALLOW");
        action.setStatus("READY");
        return action;
    }
}
