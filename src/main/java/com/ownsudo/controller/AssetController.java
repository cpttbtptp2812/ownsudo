package com.ownsudo.controller;

import com.ownsudo.entity.*;
import com.ownsudo.repository.*;
import com.ownsudo.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api")
public class AssetController {

    @Autowired
    private AgentSkillRepository skillRepository;

    @Autowired
    private McpToolRepository toolRepository;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeChunkRepository knowledgeRepository;

    @Autowired
    private AgentMemoryRepository memoryRepository;

    @Autowired
    private GatewayRouteRepository gatewayRepository;

    @Autowired
    private MerkleBlockRepository blockRepository;

    @Autowired
    private MerkleLeafRepository leafRepository;

    /**
     * 鎶€鑳藉簱 API: 鏁版嵁搴撶洿鍑?     */
    @GetMapping("/skills")
    public List<AgentSkill> getSkills() {
        return skillRepository.findByEnabledTrueOrderByCreatedAtDesc();
    }

    @GetMapping("/skills/{id}")
    public ResponseEntity<AgentSkill> getSkillById(@PathVariable String id) {
        return skillRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/skills")
    public AgentSkill saveSkill(@RequestBody AgentSkill skill) {
        return skillRepository.save(skill);
    }

    @DeleteMapping("/skills/{id}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String id) {
        skillRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true, "deletedId", id));
    }

    @PostMapping("/tools")
    public ResponseEntity<?> saveTool(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "Tool definition is required"));
        String functionName = String.valueOf(body.getOrDefault("functionName", "")).trim();
        String description = String.valueOf(body.getOrDefault("description", "")).trim();
        if (!functionName.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) return ResponseEntity.badRequest().body(Map.of("error", "functionName must be a valid identifier"));
        if (description.isBlank() || description.length() > 1024) return ResponseEntity.badRequest().body(Map.of("error", "description is required and must not exceed 1024 characters"));
        String schemaText = String.valueOf(body.getOrDefault("parametersSchema", "{}"));
        try {
            JsonNode schema = objectMapper.readTree(schemaText);
            if (schema == null || !schema.isObject()) return ResponseEntity.badRequest().body(Map.of("error", "parametersSchema must be a JSON object"));
        } catch (Exception invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", "parametersSchema must contain valid JSON"));
        }
        String id = String.valueOf(body.getOrDefault("id", "")).trim();
        if (id.isBlank()) id = "tool-" + UUID.randomUUID().toString().substring(0, 12);
        McpTool tool = toolRepository.findById(id).orElseGet(McpTool::new);
        tool.setId(id);
        tool.setServerName(String.valueOf(body.getOrDefault("serverName", "custom-plugin")));
        tool.setFunctionName(functionName);
        tool.setDisplayName(String.valueOf(body.getOrDefault("displayName", functionName)));
        tool.setDescription(description);
        tool.setCategory(String.valueOf(body.getOrDefault("category", "BUSINESS")));
        tool.setEnabled(!Boolean.FALSE.equals(body.get("enabled")));
        tool.setParametersSchema(schemaText);
        tool.setRiskLevel(String.valueOf(body.getOrDefault("riskLevel", "LOW")));
        tool.setIsSafe(body.containsKey("isSafe") ? Boolean.TRUE.equals(body.get("isSafe")) : true);
        Object timeout = body.get("timeoutMs");
        if (timeout instanceof Number number) tool.setTimeoutMs(Math.max(100, Math.min(120000, number.intValue())));
        return ResponseEntity.ok(toolRepository.save(tool));
    }

    @PostMapping("/tools/toggle/{id}")
    public ResponseEntity<?> toggleTool(@PathVariable String id) {
        return toolRepository.findById(id).map(tool -> {
            tool.setEnabled(!Boolean.TRUE.equals(tool.getEnabled()));
            toolRepository.save(tool);
            return ResponseEntity.ok(Map.of("enabled", tool.getEnabled()));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tools/test")
    public ResponseEntity<?> testTool(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.get("toolName") == null) return ResponseEntity.badRequest().body(Map.of("error", "toolName is required"));
        String toolName = body.get("toolName").toString();
        McpTool registered = toolRepository.findAll().stream()
                .filter(tool -> toolName.equals(tool.getFunctionName()) || toolName.equals(tool.getId()))
                .findFirst().orElse(null);
        if (registered == null) return ResponseEntity.notFound().build();
        if (!Boolean.TRUE.equals(registered.getEnabled())) return ResponseEntity.status(409).body(Map.of("error", "Tool is disabled"));
        boolean executable = toolExecutor.getToolDefinitions().stream().anyMatch(def -> {
            Object fn = def.get("function");
            return fn instanceof Map<?, ?> map && toolName.equals(map.get("name"));
        });
        if (!executable) return ResponseEntity.status(501).body(Map.of("error", "该工具已登记，但尚未配置执行处理器；当前仅支持已注册的内置工具"));
        Object payload = body.get("payload") == null ? Map.of() : body.get("payload");
        if (!(payload instanceof Map<?, ?>)) return ResponseEntity.badRequest().body(Map.of("error", "payload must be a JSON object"));
        try {
            ToolExecutor.ToolExecutionResult execution = toolExecutor.executeTool(toolName, objectMapper.writeValueAsString(payload), 0);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", execution.isSuccess());
            response.put("result", execution.getData());
            response.put("error", execution.getErrorMessage());
            response.put("errorType", execution.getErrorType());
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Tool test failed"));
        }
    }

    @DeleteMapping("/tools/{id}")
    public ResponseEntity<Map<String, Object>> deleteTool(@PathVariable String id) {
        if (!toolRepository.existsById(id)) return ResponseEntity.notFound().build();
        toolRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true, "deletedId", id));
    }

    @GetMapping("/tools")
    public List<McpTool> getTools() {
        List<McpTool> existing = toolRepository.findAll();
        Set<String> knownFunctions = existing.stream().map(McpTool::getFunctionName)
                .filter(name -> name != null && !name.isBlank()).collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> definition : toolExecutor.getToolDefinitions()) {
            Object rawFunction = definition.get("function");
            if (!(rawFunction instanceof Map<?, ?> function)) continue;
            String functionName = String.valueOf(function.get("name"));
            if (functionName.isBlank() || knownFunctions.contains(functionName)) continue;
            McpTool builtin = new McpTool();
            builtin.setId("builtin-" + functionName);
            builtin.setServerName("builtin");
            builtin.setFunctionName(functionName);
            builtin.setDisplayName(functionName);
            Object description = function.get("description");
            builtin.setDescription(description == null ? "" : description.toString());
            builtin.setCategory("SYSTEM");
            builtin.setRiskLevel("LOW");
            builtin.setIsSafe(true);
            builtin.setEnabled(true);
            try {
                builtin.setParametersSchema(objectMapper.writeValueAsString(function.get("parameters")));
            } catch (Exception ignored) {
                builtin.setParametersSchema("{}");
            }
            toolRepository.save(builtin);
            knownFunctions.add(functionName);
        }
        return toolRepository.findAll();
    }

    @GetMapping("/knowledge")
    public List<KnowledgeChunk> getKnowledgeChunks() {
        return knowledgeRepository.findTop20ByOrderByCreatedAtDesc();
    }

    /**
     * 鏅鸿兘浣撲笁灞傝蹇嗘绱?API: 鏁版嵁搴撶洿鍑?     */
    @GetMapping("/memory")
    public List<AgentMemory> getMemories(@RequestParam(required = false, defaultValue = "global-system") String sessionId) {
        return memoryRepository.findBySessionIdOrderByLastAccessedAtDesc(sessionId);
    }

    /**
     * 妯″瀷缃戝叧閰嶇疆 API: 鏁版嵁搴撶洿鍑?     */
    @GetMapping("/gateway/routes")
    public List<GatewayRoute> getGatewayRoutes() {
        return gatewayRepository.findAll();
    }

    /**
     * 閾惧紡 Merkle 涓嶅彲绡℃敼鍖哄潡璐︽湰 API: 鏁版嵁搴撶洿鍑?     */
    @GetMapping("/ledger/blocks")
    public List<MerkleBlock> getLedgerBlocks() {
        return blockRepository.findTop20ByOrderByBlockHeightDesc();
    }

    @GetMapping("/ledger/blocks/{blockHeight}/leaves")
    public List<MerkleLeaf> getBlockLeaves(@PathVariable Long blockHeight) {
        return leafRepository.findByBlockHeightOrderByLeafIndexAsc(blockHeight);
    }
}