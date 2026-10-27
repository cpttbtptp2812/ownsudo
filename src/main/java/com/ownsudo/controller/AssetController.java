package com.ownsudo.controller;

import com.ownsudo.entity.*;
import com.ownsudo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssetController {

    @Autowired
    private AgentSkillRepository skillRepository;

    @Autowired
    private McpToolRepository toolRepository;

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
    public McpTool saveTool(@RequestBody McpTool tool) {
        return toolRepository.save(tool);
    }

    @DeleteMapping("/tools/{id}")
    public ResponseEntity<Map<String, Object>> deleteTool(@PathVariable String id) {
        toolRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true, "deletedId", id));
    }

    /**
     * MCP 宸ュ叿娉ㄥ唽琛?API: 鏁版嵁搴撶洿鍑?     */
    @GetMapping("/tools")
    public List<McpTool> getTools() {
        return toolRepository.findByEnabledTrue();
    }

    /**
     * 鐭ヨ瘑搴撳垏鐗?API: 鏁版嵁搴撶洿鍑?     */
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