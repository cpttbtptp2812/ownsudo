package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.ActionApprovalRequest;
import com.ownsudo.dto.ActionEventResponse;
import com.ownsudo.dto.ActionResponse;
import com.ownsudo.dto.CreateActionRequest;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.security.ApprovalAuthorizationService;
import com.ownsudo.service.ActionApplicationService;
import com.ownsudo.service.ActionApplicationService.IdempotencyConflictException;
import com.ownsudo.service.ActionExecutionService;
import com.ownsudo.service.ActionExecutionStateService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/actions")
public class ActionController {

    private final ActionApplicationService actionService;
    private final ActionExecutionService executionService;
    private final ActionExecutionStateService executionState;
    private final ObjectMapper objectMapper;
    private final ApprovalAuthorizationService approvalAuthorization;

    public ActionController(ActionApplicationService actionService, ActionExecutionService executionService,
                            ActionExecutionStateService executionState, ObjectMapper objectMapper,
                            ApprovalAuthorizationService approvalAuthorization) {
        this.actionService = actionService;
        this.executionService = executionService;
        this.executionState = executionState;
        this.objectMapper = objectMapper;
        this.approvalAuthorization = approvalAuthorization;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) CreateActionRequest request) {
        try {
            ActionApplicationService.CreateResult result = actionService.create(agentId, request);
            return ResponseEntity.status(result.created() ? 201 : 200)
                    .body(ActionResponse.from(result.action(), objectMapper));
        } catch (IdempotencyConflictException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "IDEMPOTENCY_KEY_REUSED", "message", e.getMessage())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", Map.of("code", "INVALID_ACTION_REQUEST", "message", e.getMessage())));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "IDEMPOTENCY_CONFLICT", "message", "An Action with this idempotencyKey was created concurrently; retry with the same key")));
        }
    }

    @GetMapping
    public ResponseEntity<List<ActionResponse>> list(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestParam(defaultValue = "50") int limit) {
        List<ActionResponse> actions = actionService.list(agentId, limit).stream()
                .map(action -> ActionResponse.from(action, objectMapper)).toList();
        return ResponseEntity.ok(actions);
    }

    @GetMapping("/{actionId}")
    public ResponseEntity<ActionResponse> get(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId) {
        return actionService.find(agentId, actionId)
                .map(action -> ActionResponse.from(action, objectMapper))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/pending-approval")
    public ResponseEntity<?> pendingApprovals(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestHeader(name = "X-OwnAgent-Approver-Key", required = false) String approverKey) {
        if (!approvalAuthorization.isAuthorized(approverKey)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", Map.of("code", "APPROVER_FORBIDDEN", "message", "A valid approver key is required")));
        }
        return ResponseEntity.ok(actionService.pendingApprovals(agentId).stream()
                .map(action -> ActionResponse.from(action, objectMapper)).toList());
    }

    @PostMapping("/{actionId}/approve")
    public ResponseEntity<?> approve(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId,
            @RequestHeader(name = "X-OwnAgent-Approver-Key", required = false) String approverKey,
            @RequestBody(required = false) ActionApprovalRequest request) {
        if (!approvalAuthorization.isAuthorized(approverKey)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", Map.of("code", "APPROVER_FORBIDDEN", "message", "A valid approver key is required")));
        }
        try {
            return ResponseEntity.ok(ActionResponse.from(executionState.approve(
                    agentId, actionId, approvalAuthorization.getConfiguredApproverId(),
                    request == null ? null : request.reason()), objectMapper));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "APPROVAL_STATE_CONFLICT", "message", e.getMessage())));
        }
    }

    @PostMapping("/{actionId}/reject")
    public ResponseEntity<?> reject(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId,
            @RequestHeader(name = "X-OwnAgent-Approver-Key", required = false) String approverKey,
            @RequestBody(required = false) ActionApprovalRequest request) {
        if (!approvalAuthorization.isAuthorized(approverKey)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", Map.of("code", "APPROVER_FORBIDDEN", "message", "A valid approver key is required")));
        }
        try {
            return ResponseEntity.ok(ActionResponse.from(executionState.reject(
                    agentId, actionId, approvalAuthorization.getConfiguredApproverId(),
                    request == null ? null : request.reason()), objectMapper));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "APPROVAL_STATE_CONFLICT", "message", e.getMessage())));
        }
    }

    @PostMapping("/{actionId}/execute")
    public ResponseEntity<?> execute(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId) {
        try {
            return ResponseEntity.ok(ActionResponse.from(executionService.execute(agentId, actionId), objectMapper));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException | ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "ACTION_STATE_CONFLICT", "message", e.getMessage())));
        }
    }

    @GetMapping("/{actionId}/events")
    public ResponseEntity<?> events(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId) {
        if (actionService.find(agentId, actionId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(executionState.events(agentId, actionId).stream()
                .map(event -> ActionEventResponse.from(event, objectMapper)).toList());
    }
}
