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
     * Executes only a persisted, policy-approved Action through an installed server-side handler.
     * Handler execution happens outside the database transaction; state transitions are persisted separately.
     */
    public ActionEntity execute(String agentId, String actionId) {
        ActionEntity claimed = state.claim(agentId, actionId);
        try {
            Map<String, Object> arguments = mapper.readValue(
                    claimed.getArgumentsJson(), new TypeReference<Map<String, Object>>() {});
            ActionToolHandler handler = handlers.require(claimed.getToolName());
            Map<String, Object> output = handler.execute(arguments);
            return state.succeed(agentId, actionId, output);
        } catch (ActionToolHandlerRegistry.ToolHandlerNotFoundException e) {
            return state.fail(agentId, actionId, "TOOL_HANDLER_NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            return state.fail(agentId, actionId, "INVALID_TOOL_ARGUMENTS", e.getMessage());
        } catch (Exception e) {
            return state.fail(agentId, actionId, "TOOL_EXECUTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}