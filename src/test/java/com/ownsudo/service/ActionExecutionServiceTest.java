package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        when(state.succeed(eq("agent-a"), eq("act-1"), eq("lease-1"), anyMap())).thenReturn(succeeded);

        ActionToolHandlerRegistry registry = new ActionToolHandlerRegistry(java.util.List.of(new DemoEchoToolHandler()));
        ActionExecutionService service = new ActionExecutionService(state, registry, mapper, 10, 1000);
        try {
            ActionEntity result = service.execute("agent-a", "act-1");
            assertEquals("SUCCEEDED", result.getStatus());
            verify(state).claim("agent-a", "act-1");
            verify(state).succeed("agent-a", "act-1", "lease-1", Map.of("message", "hello", "characterCount", 5));
            verify(state, never()).fail(anyString(), anyString(), anyString(), anyString(), anyString());
        } finally {
            service.shutdownExecutors();
        }
    }

    @Test
    void missingHandlerFailsInsteadOfReportingSuccess() {
        when(state.claim("agent-a", "act-2")).thenReturn(readyAction("uninstalled.tool", "{}"));
        ActionEntity failed = new ActionEntity();
        failed.setStatus("FAILED");
        when(state.fail(eq("agent-a"), eq("act-2"), eq("lease-1"), eq("TOOL_HANDLER_NOT_FOUND"), contains("uninstalled.tool")))
                .thenReturn(failed);

        ActionExecutionService service = new ActionExecutionService(state,
                new ActionToolHandlerRegistry(java.util.List.of()), mapper, 10, 1000);
        try {
            ActionEntity result = service.execute("agent-a", "act-2");
            assertEquals("FAILED", result.getStatus());
            verify(state, never()).succeed(anyString(), anyString(), anyString(), anyMap());
            verify(state).fail(eq("agent-a"), eq("act-2"), eq("lease-1"), eq("TOOL_HANDLER_NOT_FOUND"), anyString());
        } finally {
            service.shutdownExecutors();
        }
    }

    @Test
    void invalidToolArgumentsArePersistedAsFailure() {
        when(state.claim("agent-a", "act-3")).thenReturn(readyAction("text.transform", "{\"text\":\"hello\",\"operation\":\"explode\"}"));
        ActionEntity failed = new ActionEntity();
        failed.setStatus("FAILED");
        when(state.fail(eq("agent-a"), eq("act-3"), eq("lease-1"), eq("INVALID_TOOL_ARGUMENTS"), anyString())).thenReturn(failed);

        ActionExecutionService service = new ActionExecutionService(state,
                new ActionToolHandlerRegistry(java.util.List.of(new TextTransformToolHandler())), mapper, 10, 1000);
        try {
            ActionEntity result = service.execute("agent-a", "act-3");
            assertEquals("FAILED", result.getStatus());
            verify(state, never()).succeed(anyString(), anyString(), anyString(), anyMap());
        } finally {
            service.shutdownExecutors();
        }
    }

    @Test
    void timeoutCancelsHandlerAndPersistsUnknownInsteadOfFalseFailure() {
        when(state.claim("agent-a", "act-timeout")).thenReturn(readyAction("slow.tool", "{}"));
        ActionEntity unknown = new ActionEntity();
        unknown.setStatus("UNKNOWN");
        when(state.markUnknown(eq("agent-a"), eq("act-timeout"), eq("lease-1"),
                eq("EXECUTION_TIMEOUT"), contains("external side effect"))).thenReturn(unknown);

        ActionToolHandler slowHandler = new ActionToolHandler() {
            @Override public String toolName() { return "slow.tool"; }
            @Override public Map<String, Object> execute(Map<String, Object> arguments) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                return Map.of("late", true);
            }
        };
        ActionExecutionService service = new ActionExecutionService(state,
                new ActionToolHandlerRegistry(java.util.List.of(slowHandler)), mapper, 1, 1000);
        try {
            ActionEntity result = service.execute("agent-a", "act-timeout");
            assertEquals("UNKNOWN", result.getStatus());
            verify(state).markUnknown(eq("agent-a"), eq("act-timeout"), eq("lease-1"),
                    eq("EXECUTION_TIMEOUT"), contains("external side effect"));
            verify(state, never()).succeed(anyString(), anyString(), anyString(), anyMap());
            verify(state, never()).fail(anyString(), anyString(), anyString(), anyString(), anyString());
        } finally {
            service.shutdownExecutors();
        }
    }

    private ActionEntity readyAction(String tool, String arguments) {
        ActionEntity action = new ActionEntity();
        action.setId("act-1");
        action.setAgentId("agent-a");
        action.setToolName(tool);
        action.setArgumentsJson(arguments);
        action.setDecision("ALLOW");
        action.setStatus("READY");
        action.setLeaseToken("lease-1");
        return action;
    }
}
