package com.ownsudo.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.WorkerClaimRequest;
import com.ownsudo.dto.WorkerClaimResponse;
import com.ownsudo.dto.WorkerCompleteRequest;
import com.ownsudo.dto.WorkerFailRequest;
import com.ownsudo.dto.WorkerLeaseRequest;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.service.ActionExecutionStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/worker/actions")
public class WorkerController {
    private final ActionExecutionStateService executionState;
    private final ObjectMapper mapper;

    public WorkerController(ActionExecutionStateService executionState, ObjectMapper mapper) {
        this.executionState = executionState;
        this.mapper = mapper;
    }

    /**
     * Claims one ALLOW/READY Action for a tool installed in this Worker.
     * The Agent API key scopes the Worker to the same Agent's Actions.
     */
    @PostMapping("/claim")
    public ResponseEntity<?> claim(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) WorkerClaimRequest request) {
        if (request == null || request.toolNames() == null || request.toolNames().isEmpty()
                || request.toolNames().size() > 100) {
            return ResponseEntity.badRequest().body(error("INVALID_WORKER_CLAIM",
                    "toolNames must contain between 1 and 100 installed tool names"));
        }
        Optional<ActionEntity> claimed = executionState.claimNext(agentId, request.toolNames());
        if (claimed.isEmpty()) return ResponseEntity.noContent().build();
        ActionEntity action = claimed.get();
        try {
            Map<String, Object> arguments = mapper.readValue(action.getArgumentsJson(),
                    new TypeReference<Map<String, Object>>() {});
            return ResponseEntity.ok(new WorkerClaimResponse(action.getId(), action.getToolName(),
                    arguments, action.getLeaseToken(), action.getLeaseUntil(), action.getAttemptCount()));
        } catch (Exception e) {
            executionState.fail(agentId, action.getId(), action.getLeaseToken(),
                    "INVALID_TOOL_ARGUMENTS", "Stored Action arguments could not be decoded");
            return ResponseEntity.internalServerError().body(error("INVALID_TOOL_ARGUMENTS",
                    "Stored Action arguments could not be decoded"));
        }
    }

    @PostMapping("/{actionId}/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId,
            @RequestBody(required = false) WorkerLeaseRequest request) {
        if (request == null || request.leaseToken() == null || request.leaseToken().isBlank()) {
            return ResponseEntity.badRequest().body(error("INVALID_LEASE", "leaseToken is required"));
        }
        try {
            ActionEntity renewed = executionState.renewLease(agentId, actionId, request.leaseToken());
            return ResponseEntity.ok(Map.of("actionId", actionId, "leaseUntil", renewed.getLeaseUntil()));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            return ResponseEntity.status(409).body(error("LEASE_NOT_ACTIVE", e.getMessage()));
        }
    }

    @PostMapping("/{actionId}/complete")
    public ResponseEntity<?> complete(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId,
            @RequestBody(required = false) WorkerCompleteRequest request) {
        if (request == null || request.leaseToken() == null || request.leaseToken().isBlank()
                || request.output() == null) {
            return ResponseEntity.badRequest().body(error("INVALID_WORKER_RESULT",
                    "leaseToken and output object are required"));
        }
        try {
            return ResponseEntity.ok(executionState.succeed(agentId, actionId,
                    request.leaseToken(), request.output()));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            return ResponseEntity.status(409).body(error("LEASE_NOT_ACTIVE", e.getMessage()));
        }
    }

    @PostMapping("/{actionId}/unknown")
    public ResponseEntity<?> unknown(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId,
            @RequestBody(required = false) WorkerFailRequest request) {
        if (request == null || request.leaseToken() == null || request.leaseToken().isBlank()
                || request.errorCode() == null || !request.errorCode().matches("[A-Z0-9_]{1,64}")) {
            return ResponseEntity.badRequest().body(error("INVALID_WORKER_OUTCOME",
                    "leaseToken and an uppercase errorCode are required"));
        }
        try {
            return ResponseEntity.ok(executionState.markUnknown(agentId, actionId, request.leaseToken(),
                    request.errorCode(), request.errorMessage()));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            return ResponseEntity.status(409).body(error("LEASE_NOT_ACTIVE", e.getMessage()));
        }
    }

    @PostMapping("/{actionId}/fail")
    public ResponseEntity<?> fail(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId,
            @RequestBody(required = false) WorkerFailRequest request) {
        if (request == null || request.leaseToken() == null || request.leaseToken().isBlank()
                || request.errorCode() == null || !request.errorCode().matches("[A-Z0-9_]{1,64}")) {
            return ResponseEntity.badRequest().body(error("INVALID_WORKER_FAILURE",
                    "leaseToken and an uppercase errorCode are required"));
        }
        try {
            return ResponseEntity.ok(executionState.fail(agentId, actionId, request.leaseToken(),
                    request.errorCode(), request.errorMessage()));
        } catch (ActionExecutionStateService.ActionNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            return ResponseEntity.status(409).body(error("LEASE_NOT_ACTIVE", e.getMessage()));
        }
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
