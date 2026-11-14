package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownsudo.dto.CreateActionRequest;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.entity.AgentToolEntity;
import com.ownsudo.repository.ActionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class ActionApplicationService {
    private static final int MAX_LIMIT = 100;
    private final ActionRepository actionRepository;
    private final ObjectMapper objectMapper;
    private final ToolRegistryService toolRegistry;
    private final ActionPolicyEngine policyEngine;

    public ActionApplicationService(ActionRepository actionRepository, ObjectMapper objectMapper,
                                    ToolRegistryService toolRegistry, ActionPolicyEngine policyEngine) {
        this.actionRepository = actionRepository;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.policyEngine = policyEngine;
    }

    @Transactional
    public CreateResult create(String authenticatedAgentId, CreateActionRequest request) {
        if (authenticatedAgentId == null || authenticatedAgentId.isBlank())
            throw new IllegalArgumentException("Authenticated Agent identity is required");
        if (request == null) throw new IllegalArgumentException("Request body is required");
        String toolName = request.getToolName() == null ? "" : request.getToolName().trim();
        if (!toolName.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("toolName must be 1-128 characters and use letters, digits, '_', '.', ':', or '-'");
        String idempotencyKey = request.getIdempotencyKey() == null ? "" : request.getIdempotencyKey().trim();
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new IllegalArgumentException("idempotencyKey is required and must not exceed 128 characters");
        if (request.getArguments() == null)
            throw new IllegalArgumentException("arguments is required and must be a JSON object");

        JsonNode arguments = sortJson(objectMapper.valueToTree(request.getArguments()));
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("toolName", toolName);
        operation.set("arguments", arguments);
        String canonicalArguments = writeJson(arguments);
        String requestHash = sha256(writeJson(operation));
        String metadataJson = request.getMetadata() == null ? null
                : writeJson(sortJson(objectMapper.valueToTree(request.getMetadata())));

        Optional<ActionEntity> existing = actionRepository.findByAgentIdAndIdempotencyKey(authenticatedAgentId, idempotencyKey);
        if (existing.isPresent()) {
            ActionEntity previous = existing.get();
            if (!MessageDigest.isEqual(previous.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new IdempotencyConflictException("This idempotencyKey has already been used with a different toolName or arguments");
            }
            return new CreateResult(previous, false);
        }

        AgentToolEntity registeredTool = toolRegistry.find(authenticatedAgentId, toolName).orElse(null);
        ActionPolicyEngine.PolicyDecision policy = policyEngine.evaluate(registeredTool);
        if (registeredTool != null && !"DENY".equals(policy.decision())) {
            toolRegistry.validateArguments(registeredTool, request.getArguments());
        }

        ActionEntity action = new ActionEntity();
        action.setId("act-" + UUID.randomUUID());
        action.setAgentId(authenticatedAgentId);
        action.setToolName(toolName);
        action.setArgumentsJson(canonicalArguments);
        action.setMetadataJson(metadataJson);
        action.setIdempotencyKey(idempotencyKey);
        action.setRequestHash(requestHash);
        action.setStatus(policy.actionStatus());
        action.setDecision(policy.decision());
        action.setRiskLevel(policy.riskLevel());
        action.setApprovalStatus("REQUIRE_APPROVAL".equals(policy.decision()) ? "PENDING" : "NOT_REQUIRED");
        return new CreateResult(actionRepository.save(action), true);
    }

    @Transactional(readOnly = true)
    public Optional<ActionEntity> find(String authenticatedAgentId, String actionId) {
        return actionRepository.findByIdAndAgentId(actionId, authenticatedAgentId);
    }

    @Transactional(readOnly = true)
    public List<ActionEntity> list(String authenticatedAgentId, int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        return actionRepository.findAllByAgentIdOrderByCreatedAtDesc(authenticatedAgentId, PageRequest.of(0, limit));
    }

    private JsonNode sortJson(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(sortJson(value)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) result.set(name, sortJson(node.get(name)));
            return result;
        }
        return node;
    }

    private String writeJson(JsonNode node) {
        try { return objectMapper.writeValueAsString(node); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Action request contains data that cannot be serialized as JSON", e);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    public record CreateResult(ActionEntity action, boolean created) {}
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) { super(message); }
    }
}
