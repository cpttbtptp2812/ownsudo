package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.RegisterToolRequest;
import com.ownsudo.dto.ToolResponse;
import com.ownsudo.security.AgentApiKeyFilter;
import com.ownsudo.security.ToolRegistryAuthorizationService;
import com.ownsudo.service.ToolRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {
    private final ToolRegistryService tools;
    private final ObjectMapper mapper;
    private final ToolRegistryAuthorizationService registryAuthorization;
    public ToolController(ToolRegistryService tools,ObjectMapper mapper, ToolRegistryAuthorizationService registryAuthorization){this.tools=tools;this.mapper=mapper;this.registryAuthorization=registryAuthorization;}

    @PostMapping
    public ResponseEntity<?> register(@RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
                                      @RequestHeader(value="X-OwnAgent-Tool-Admin-Key", required=false) String adminKey,
                                      @RequestBody(required=false) RegisterToolRequest request) {
        if (!registryAuthorization.isAuthorized(adminKey)) return ResponseEntity.status(403).body(Map.of("error",Map.of("code","TOOL_ADMIN_FORBIDDEN","message","A valid developer tool-admin key is required")));
        try { return ResponseEntity.status(201).body(ToolResponse.from(tools.register(agentId,request),mapper)); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",Map.of("code","INVALID_TOOL_DEFINITION","message",e.getMessage()))); }
    }
    @GetMapping
    public ResponseEntity<List<ToolResponse>> list(@RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId) {
        return ResponseEntity.ok(tools.list(agentId).stream().map(t->ToolResponse.from(t,mapper)).toList());
    }
}