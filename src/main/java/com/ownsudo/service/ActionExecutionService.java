package com.ownsudo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ActionExecutionService {
    private final ActionExecutionStateService state;
    private final ActionToolHandlerRegistry handlers;
    private final ObjectMapper mapper;

    public ActionExecutionService(ActionExecutionStateService state, ActionToolHandlerRegistry handlers, ObjectMapper mapper) {
        this.state = state;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    /**
     * Executes an allowlisted handler outside the state transaction.
     * The lease token prevents a stale executor from overwriting a recovered Action.
     */
    public ActionEntity execute(String agentId, String actionId) {
        ActionEntity claimed = state.claim(agentId, actionId);
        String leaseToken = claimed.getLeaseToken();
        try {
            Map<String, Object> arguments = mapper.readValue(
                    claimed.getArgumentsJson(), new TypeReference<Map<String, Object>>() {});
            ActionToolHandler handler = handlers.require(claimed.getToolName());
            Map<String, Object> output = handler.execute(arguments);
            return state.succeed(agentId, actionId, leaseToken, output);
        } catch (ActionToolHandlerRegistry.ToolHandlerNotFoundException e) {
            return state.fail(agentId, actionId, leaseToken, "TOOL_HANDLER_NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            return state.fail(agentId, actionId, leaseToken, "INVALID_TOOL_ARGUMENTS", e.getMessage());
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            throw e;
        } catch (Exception e) {
            return state.fail(agentId, actionId, leaseToken, "TOOL_EXECUTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
