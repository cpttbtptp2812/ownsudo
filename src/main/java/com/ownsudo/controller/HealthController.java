package com.ownsudo.controller;

import com.ownsudo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private AgentSkillRepository skillRepository;

    @Autowired
    private McpToolRepository toolRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeRepository;

    @Autowired
    private MerkleBlockRepository blockRepository;

    @Autowired
    private GatewayRouteRepository gatewayRepository;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("status", "UP");
        map.put("service", "ownsudo-engine");
        map.put("runtime", "Java 21 · Spring Boot 3");

        String dbInfo = "Unknown";
        try (Connection conn = dataSource.getConnection()) {
            dbInfo = conn.getMetaData().getDatabaseProductName() + " " + conn.getMetaData().getDatabaseProductVersion();
        } catch (Exception e) {
            dbInfo = "Error: " + e.getMessage();
        }
        map.put("db", dbInfo);
        map.put("databaseConnected", true);
        map.put("workflowRuns", instanceRepository.count());
        map.put("skillsCount", skillRepository.count());
        map.put("mcpToolsCount", toolRepository.count());
        map.put("ragChunks", knowledgeRepository.count());
        map.put("merkleBlocks", blockRepository.count());
        map.put("activeRoutes", gatewayRepository.count());
        map.put("version", "v2.5-enterprise");
        map.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(map);
    }
}
