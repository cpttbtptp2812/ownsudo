package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.entity.ActionEventEntity;
import com.ownsudo.repository.ActionEventRepository;
import com.ownsudo.repository.ActionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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
    private final long leaseSeconds;

    public ActionExecutionStateService(ActionRepository actions, ActionEventRepository events, ObjectMapper mapper,
                                       @Value("${ownagent.runtime.lease-seconds:300}") long leaseSeconds) {
        this.actions = actions;
        this.events = events;
        this.mapper = mapper;
        this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    @Transactional
    public ActionEntity claim(String agentId, String actionId) {
        ActionEntity action = actions.findByIdAndAgentId(actionId, agentId)
                .orElseThrow(() -> new ActionNotFoundException("Action was not found"));
        return claimAction(action);
    }

    /**
     * Atomically claims the oldest READY Action whose tool is installed by this Worker.
     * The repository query takes a pessimistic row lock so two Workers cannot claim the same row.
     */
    @Transactional
    public Optional<ActionEntity> claimNext(String agentId, List<String> supportedTools) {
        if (supportedTools == null || supportedTools.isEmpty()) return Optional.empty();
        List<String> safeTools = supportedTools.stream().filter(name -> name != null
                && name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")).distinct().limit(100).toList();
        if (safeTools.isEmpty()) return Optional.empty();
        return actions.findFirstByAgentIdAndDecisionAndStatusAndToolNameInOrderByCreatedAtAsc(
                agentId, "ALLOW", "READY", safeTools).map(this::claimAction);
    }

    private ActionEntity claimAction(ActionEntity action) {
        if (!"ALLOW".equals(action.getDecision()) || !"READY".equals(action.getStatus())) {
            throw new ActionStateConflictException("Only Actions with decision ALLOW and status READY can be executed");
        }
        LocalDateTime now = LocalDateTime.now();
        action.setStatus("RUNNING");
        action.setAttemptCount(action.getAttemptCount() + 1);
        action.setStartedAt(now);
        action.setCompletedAt(null);
        action.setErrorCode(null);
        action.setErrorMessage(null);
        action.setLeaseToken(UUID.randomUUID().toString());
        action.setLeaseUntil(now.plusSeconds(leaseSeconds));
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(saved.getAgentId(), saved.getId(), "ACTION_STARTED", Map.of(
                "attempt", saved.getAttemptCount(), "leaseUntil", saved.getLeaseUntil().toString(),
                "executionMode", "LOCAL_WORKER"));
        return saved;
    }

    /**
     * Extends only the currently active lease. An expired lease is never resurrected.
     */
    @Transactional
    public ActionEntity renewLease(String agentId, String actionId, String leaseToken) {
        ActionEntity action = requireActiveLease(agentId, actionId, leaseToken);
        action.setLeaseUntil(LocalDateTime.now().plusSeconds(leaseSeconds));
        return actions.saveAndFlush(action);
    }

    @Transactional
    public ActionEntity succeed(String agentId, String actionId, String leaseToken, Map<String, Object> output) {
        ActionEntity action = requireActiveLease(agentId, actionId, leaseToken);
        action.setStatus("SUCCEEDED");
        action.setOutputJson(writeJson(output));
        action.setErrorCode(null);
        action.setErrorMessage(null);
        action.setCompletedAt(LocalDateTime.now());
        clearLease(action);
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(agentId, actionId, "ACTION_SUCCEEDED", Map.of("attempt", saved.getAttemptCount()));
        return saved;
    }

    @Transactional
    public ActionEntity fail(String agentId, String actionId, String leaseToken, String errorCode, String message) {
        ActionEntity action = requireActiveLease(agentId, actionId, leaseToken);
        action.setStatus("FAILED");
        action.setOutputJson(null);
        action.setErrorCode(errorCode);
        action.setErrorMessage(message == null ? "Action execution failed" : message.substring(0, Math.min(message.length(), 1000)));
        action.setCompletedAt(LocalDateTime.now());
        clearLease(action);
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(agentId, actionId, "ACTION_FAILED", Map.of(
                "attempt", saved.getAttemptCount(), "errorCode", errorCode));
        return saved;
    }

    /**
     * A timeout/interruption has an uncertain side-effect outcome. The matching execution
     * may mark UNKNOWN even if its lease deadline has just elapsed, but it cannot mark success.
     */
    @Transactional
    public ActionEntity markUnknown(String agentId, String actionId, String leaseToken, String errorCode, String message) {
        ActionEntity action = actions.findByIdAndAgentId(actionId, agentId)
                .orElseThrow(() -> new ActionNotFoundException("Action was not found"));
        if (!"RUNNING".equals(action.getStatus()) || leaseToken == null
                || !leaseToken.equals(action.getLeaseToken())) {
            throw new ActionStateConflictException("Action execution lease is no longer owned by this executor");
        }
        action.setStatus("UNKNOWN");
        action.setErrorCode(errorCode);
        action.setErrorMessage(message == null ? "Action outcome is unknown" : message.substring(0, Math.min(message.length(), 1000)));
        action.setCompletedAt(LocalDateTime.now());
        clearLease(action);
        ActionEntity saved = actions.saveAndFlush(action);
        appendEvent(agentId, actionId, "ACTION_EXECUTION_UNKNOWN", Map.of(
                "attempt", saved.getAttemptCount(), "errorCode", errorCode));
        return saved;
    }

    /**
     * A lease expiry means the process may have died before or after an external side effect.
     * Mark UNKNOWN and never put the Action back into READY automatically.
     */
    @Transactional
    public int recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        List<ActionEntity> expired = actions.findAllByStatusAndLeaseUntilBefore(
                "RUNNING", now, PageRequest.of(0, 100));
        int recovered = 0;
        for (ActionEntity action : expired) {
            if (!"RUNNING".equals(action.getStatus()) || action.getLeaseUntil() == null
                    || action.getLeaseUntil().isAfter(now)) continue;
            action.setStatus("UNKNOWN");
            action.setErrorCode("EXECUTION_LEASE_EXPIRED");
            action.setErrorMessage("The execution lease expired. The external side-effect outcome is unknown; manual reconciliation is required before retry.");
            action.setCompletedAt(now);
            clearLease(action);
            ActionEntity saved = actions.saveAndFlush(action);
            appendEvent(saved.getAgentId(), saved.getId(), "ACTION_LEASE_EXPIRED", Map.of(
                    "attempt", saved.getAttemptCount(),
                    "recovery", "UNKNOWN_REQUIRES_RECONCILIATION"));
            recovered++;
        }
        return recovered;
    }

    @Transactional(readOnly = true)
    public Optional<ActionEntity> find(String agentId, String actionId) {
        return actions.findByIdAndAgentId(actionId, agentId);
    }

    @Transactional(readOnly = true)
    public List<ActionEventEntity> events(String agentId, String actionId) {
        return events.findAllByAgentIdAndActionIdOrderByCreatedAtAsc(agentId, actionId);
    }

    private ActionEntity requireActiveLease(String agentId, String actionId, String leaseToken) {
        ActionEntity action = actions.findByIdAndAgentId(actionId, agentId)
                .orElseThrow(() -> new ActionNotFoundException("Action was not found"));
        if (!"RUNNING".equals(action.getStatus()) || leaseToken == null
                || !leaseToken.equals(action.getLeaseToken())
                || action.getLeaseUntil() == null || !action.getLeaseUntil().isAfter(LocalDateTime.now())) {
            throw new ActionStateConflictException("Action execution lease is no longer active");
        }
        return action;
    }

    private void clearLease(ActionEntity action) {
        action.setLeaseToken(null);
        action.setLeaseUntil(null);
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
