package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.RegisterToolRequest;
import com.ownsudo.dto.ToolResponse;
import com.ownsudo.security.AgentApiKeyFilter;
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
    public ToolController(ToolRegistryService tools,ObjectMapper mapper){this.tools=tools;this.mapper=mapper;}

    @PostMapping
    public ResponseEntity<?> register(@RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId,
                                      @RequestBody(required=false) RegisterToolRequest request) {
        try { return ResponseEntity.status(201).body(ToolResponse.from(tools.register(agentId,request),mapper)); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",Map.of("code","INVALID_TOOL_DEFINITION","message",e.getMessage()))); }
    }
    @GetMapping
    public ResponseEntity<List<ToolResponse>> list(@RequestAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE) String agentId) {
        return ResponseEntity.ok(tools.list(agentId).stream().map(t->ToolResponse.from(t,mapper)).toList());
    }
}