package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.ActionResponse;
import com.ownsudo.dto.CreateActionRequest;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.service.ActionApplicationService;
import com.ownsudo.service.ActionApplicationService.IdempotencyConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/actions")
public class ActionController {

    private final ActionApplicationService actionService;
    private final ObjectMapper objectMapper;

    public ActionController(ActionApplicationService actionService, ObjectMapper objectMapper) {
        this.actionService = actionService;
        this.objectMapper = objectMapper;
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
            // A concurrent request may win the same Agent-scoped unique idempotency key.
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "IDEMPOTENCY_CONFLICT", "message", "An Action with this idempotencyKey was created concurrently; retry with the same key")));
        }
    }

    @GetMapping
    public ResponseEntity<List<ActionResponse>> list(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestParam(defaultValue = "50") int limit) {
        List<ActionResponse> actions = actionService.list(agentId, limit).stream()
                .map(action -> ActionResponse.from(action, objectMapper))
                .toList();
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
}
