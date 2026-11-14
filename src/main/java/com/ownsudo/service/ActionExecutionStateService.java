package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.entity.ActionEventEntity;
import com.ownsudo.repository.ActionEventRepository;
import com.ownsudo.repository.ActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActionExecutionStateService {
    private final ActionRepository actions;
    private final ActionEventRepository events;
    private final ObjectMapper mapper;

    public ActionExecutionStateService(ActionRepository actions, ActionEventRepository events, ObjectMapper mapper) {
        this.actions = actions;
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional
    public ActionEntity claim(String agentId, String actionId) {
        ActionEntity action = actions.findByIdAndAgentId(actionId, agentId)
                .orElseThrow(() -> new ActionNotFoundException("Action was not found"));
        if (!"ALLOW".equals(action.getDecision()) || !"READY".equals(action.getStatus())) {
            throw new ActionStateConflictException("Only Actions with decision ALLOW and status READY can be executed");
        }
        action.setStatus("RUNNING");
        action.setAttemptCount(action.getAttemptCount() + 1);
        action.setStartedAt(LocalDateTime.now());
        action.setCompletedAt(null);
        action.setErrorCode(null);
        action.setErrorMessage(null);
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(agentId, actionId, "ACTION_STARTED", Map.of("attempt", saved.getAttemptCount()));
        return saved;
    }

    @Transactional
    public ActionEntity succeed(String agentId, String actionId, Map<String, Object> output) {
        ActionEntity action = requireRunning(agentId, actionId);
        action.setStatus("SUCCEEDED");
        action.setOutputJson(writeJson(output));
        action.setErrorCode(null);
        action.setErrorMessage(null);
        action.setCompletedAt(LocalDateTime.now());
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(agentId, actionId, "ACTION_SUCCEEDED", Map.of("attempt", saved.getAttemptCount()));
        return saved;
    }

    @Transactional
    public ActionEntity fail(String agentId, String actionId, String errorCode, String message) {
        ActionEntity action = requireRunning(agentId, actionId);
        action.setStatus("FAILED");
        action.setOutputJson(null);
        action.setErrorCode(errorCode);
        action.setErrorMessage(message == null ? "Action execution failed" : message.substring(0, Math.min(message.length(), 1000)));
        action.setCompletedAt(LocalDateTime.now());
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(agentId, actionId, "ACTION_FAILED", Map.of(
                "attempt", saved.getAttemptCount(), "errorCode", errorCode));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<ActionEntity> find(String agentId, String actionId) {
        return actions.findByIdAndAgentId(actionId, agentId);
    }

    @Transactional(readOnly = true)
    public List<ActionEventEntity> events(String agentId, String actionId) {
        return events.findAllByAgentIdAndActionIdOrderByCreatedAtAsc(agentId, actionId);
    }

    private ActionEntity requireRunning(String agentId, String actionId) {
        ActionEntity action = actions.findByIdAndAgentId(actionId, agentId)
                .orElseThrow(() -> new ActionNotFoundException("Action was not found"));
        if (!"RUNNING".equals(action.getStatus())) {
            throw new ActionStateConflictException("Action is no longer in RUNNING state");
        }
        return action;
    }

    private void appendEvent(String agentId, String actionId, String type, Map<String, Object> details) {
        ActionEventEntity event = new ActionEventEntity();
        event.setId("evt-" + UUID.randomUUID());
        event.setAgentId(agentId);
        event.setActionId(actionId);
        event.setEventType(type);
        event.setDetailJson(writeJson(details));
        events.save(event);
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize Action execution data", e); }
    }

    public static class ActionNotFoundException extends RuntimeException {
        public ActionNotFoundException(String message) { super(message); }
    }
    public static class ActionStateConflictException extends RuntimeException {
        public ActionStateConflictException(String message) { super(message); }
    }
}