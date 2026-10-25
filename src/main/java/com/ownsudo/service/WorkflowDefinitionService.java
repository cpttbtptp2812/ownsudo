package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.repository.WorkflowDefinitionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkflowDefinitionService {

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void initSeedWorkflows() {
        if (definitionRepository.count() == 0) {
            WorkflowDefinition def = new WorkflowDefinition();
            def.setId("wf-settlement-migration");
            def.setName("跨行资金清算与异构数据库高危迁移流水线 (P0 2PC Gate)");
            def.setDescription("金融核心结算与主库 DDL/DML 变更工作流，内置零信任 2PC 人机协同闸口与 MCP 工具调用");
            def.setVersion("v1.2.0");

            List<Map<String, Object>> nodes = List.of(
                Map.of(
                    "id", "node-ingest-spec",
                    "name", "交易规范解析与协议合规自检",
                    "type", "POLICY_VERIFICATION",
                    "description", "检验金融清算报文与 DDL Schema 安全约束",
                    "risk", "LOW"
                ),
                Map.of(
                    "id", "node-risk-analysis",
                    "name", "大模型风险沙箱与意图影响推演",
                    "type", "LLM_REASONING",
                    "description", "LLM 评估高危越权与资损敞口，计算风险评分 (0.94)",
                    "risk", "HIGH"
                ),
                Map.of(
                    "id", "node-2pc-gate",
                    "name", "零信任 2PC 人机双签闸口 (Zero-Trust Gate)",
                    "type", "HUMAN_APPROVAL_2PC",
                    "description", "拦截自动调度，强制审计工程师电子签名授权后提交",
                    "risk", "CRITICAL"
                ),
                Map.of(
                    "id", "node-mcp-sql-exec",
                    "name", "MCP 协议分布式数据库执行器",
                    "type", "MCP_TOOL_CALL",
                    "description", "安全连接远端 MySQL 数据库执行分库分表 DDL 与账务划转",
                    "risk", "HIGH"
                ),
                Map.of(
                    "id", "node-audit-ledger",
                    "name", "防篡改加密审计凭证存证",
                    "type", "SQL_EXECUTOR",
                    "description", "生成 SHA-256 不可抵赖数字存证收据并归档持久化",
                    "risk", "LOW"
                )
            );

            try {
                def.setTopologyJson(objectMapper.writeValueAsString(nodes));
                definitionRepository.save(def);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public List<WorkflowDefinition> getAllDefinitions() {
        return definitionRepository.findAll();
    }

    public Optional<WorkflowDefinition> getById(String id) {
        return definitionRepository.findById(id);
    }

    public WorkflowDefinition save(WorkflowDefinition def) {
        if (def.getId() == null || def.getId().isBlank()) {
            def.setId("wf-" + UUID.randomUUID().toString().substring(0, 8));
        }
        return definitionRepository.save(def);
    }
}
