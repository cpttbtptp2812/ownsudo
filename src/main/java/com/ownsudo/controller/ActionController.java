package com.ownsudo.controller;

import com.ownsudo.dto.ActionResponse;
import com.ownsudo.dto.CreateActionRequest;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.service.ActionApplicationService;
import com.ownsudo.service.ActionApplicationService.IdempotencyConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/actions")
public class ActionController {

    private final ActionApplicationService actionService;

    public ActionController(ActionApplicationService actionService) {
        this.actionService = actionService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestBody(required = false) CreateActionRequest request) {
        try {
            ActionApplicationService.CreateResult result = actionService.create(agentId, request);
            return ResponseEntity.status(result.created() ? 201 : 200)
                    .body(ActionResponse.from(result.action()));
        } catch (IdempotencyConflictException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("code", "IDEMPOTENCY_KEY_REUSED", "message", e.getMessage())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", Map.of("code", "INVALID_ACTION_REQUEST", "message", e.getMessage())));
        }
    }

    @GetMapping
    public ResponseEntity<List<ActionResponse>> list(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @RequestParam(defaultValue = "50") int limit) {
        List<ActionResponse> actions = actionService.list(agentId, limit).stream()
                .map(ActionResponse::from)
                .toList();
        return ResponseEntity.ok(actions);
    }

    @GetMapping("/{actionId}")
    public ResponseEntity<ActionResponse> get(
            @RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
            @PathVariable String actionId) {
        return actionService.find(agentId, actionId)
                .map(ActionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
