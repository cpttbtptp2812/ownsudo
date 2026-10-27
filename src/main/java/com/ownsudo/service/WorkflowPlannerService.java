package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.*;
import com.ownsudo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class WorkflowPlannerService {

    @Autowired
    private AgentSkillRepository skillRepository;

    @Autowired
    private McpToolRepository toolRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeRepository;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private TarjanSccService tarjanSccService;

    @Autowired
    private ObjectMapper objectMapper;

    public static class PlanResult {
        private WorkflowDefinition definition;
        private TarjanSccService.TarjanReport tarjanReport;
        private String domain;
        private double estimatedRisk;
        private List<String> matchedTools;
        private List<String> matchedKnowledge;

        public PlanResult(WorkflowDefinition definition, TarjanSccService.TarjanReport tarjanReport, String domain, double estimatedRisk, List<String> matchedTools, List<String> matchedKnowledge) {
            this.definition = definition;
            this.tarjanReport = tarjanReport;
            this.domain = domain;
            this.estimatedRisk = estimatedRisk;
            this.matchedTools = matchedTools;
            this.matchedKnowledge = matchedKnowledge;
        }

        public WorkflowDefinition getDefinition() { return definition; }
        public TarjanSccService.TarjanReport getTarjanReport() { return tarjanReport; }
        public String getDomain() { return domain; }
        public double getEstimatedRisk() { return estimatedRisk; }
        public List<String> getMatchedTools() { return matchedTools; }
        public List<String> getMatchedKnowledge() { return matchedKnowledge; }
    }

    public PlanResult planDynamicWorkflow(String userPrompt, String department, String operator) {
        if (userPrompt == null || userPrompt.isBlank()) {
            userPrompt = "通用自动化业务核对与数据变更任务";
        }
        if (department == null || department.isBlank()) department = "Enterprise-Operations";
        if (operator == null || operator.isBlank()) operator = "SecOps-Director";

        String pLower = userPrompt.toLowerCase();
        String domain = "GENERAL_OPS";
        double estimatedRisk = 0.45;
        List<String> matchedTools = new ArrayList<>();
        List<String> matchedKnowledge = new ArrayList<>();

        if (pLower.contains("清算") || pLower.contains("转账") || pLower.contains("资金") || pLower.contains("发票") || pLower.contains("报销")) {
            domain = "FINANCE";
            estimatedRisk = 0.94;
            matchedTools.add("tool-mysql-ddl");
            matchedTools.add("tool-iso20022");
            matchedTools.add("tool-fapiao-ocr");
            matchedKnowledge.add("中国人民银行大额支付系统 (HVPS) 跨行资金清算管理办法");
        } else if (pLower.contains("iam") || pLower.contains("安全") || pLower.contains("权限") || pLower.contains("云") || pLower.contains("terraform")) {
            domain = "SECOPS";
            estimatedRisk = 0.88;
            matchedTools.add("tool-terraform-apply");
            matchedTools.add("tool-mysql-ddl");
            matchedKnowledge.add("多云微服务安全体系：IAM 最小特权与跨租户渗透防御指南");
        } else if (pLower.contains("工单") || pLower.contains("客服") || pLower.contains("sla") || pLower.contains("分派")) {
            domain = "CUSTOMER_SERVICE";
            estimatedRisk = 0.35;
            matchedTools.add("tool-mysql-ddl");
        } else if (pLower.contains("排班") || pLower.contains("门店") || pLower.contains("班次")) {
            domain = "OPS";
            estimatedRisk = 0.28;
            matchedTools.add("tool-mysql-ddl");
        }

        // 动态构建 5 节点 DAG 蓝图
        String dynamicDefId = "wf-dyn-" + UUID.randomUUID().toString().substring(0, 8);
        String dynamicName = "【动态规划】" + (userPrompt.length() > 24 ? userPrompt.substring(0, 24) + "..." : userPrompt);

        List<Map<String, Object>> topologyNodes = new ArrayList<>();

        // Step 1: 规范解析
        topologyNodes.add(Map.of(
            "id", "node-ingest-spec",
            "name", "意图解构与合规基线静态核验",
            "type", "POLICY_VERIFICATION",
            "risk", "LOW",
            "description", "解构用户业务诉求，匹配知识库制度要求与参数 Schema"
        ));

        // Step 2: 风险沙箱
        topologyNodes.add(Map.of(
            "id", "node-risk-analysis",
            "name", "多模型风险沙箱与影响推演",
            "type", "LLM_REASONING",
            "risk", estimatedRisk > 0.6 ? "HIGH" : "LOW",
            "description", "LLM 评估操作影响范围，动态计算综合风险评分 (" + String.format("%.2f", estimatedRisk) + ")"
        ));

        // Step 3: 2PC 闸口 (高危必阻断)
        if (estimatedRisk > 0.5) {
            topologyNodes.add(Map.of(
                "id", "node-2pc-gate",
                "name", "零信任 2PC 人机双签闸口 (Zero-Trust Gate)",
                "type", "HUMAN_APPROVAL_2PC",
                "risk", "CRITICAL",
                "description", "拦截自动调度，强制要求具备审计权限人员加盖私钥数字签名"
            ));
        }

        // Step 4: MCP 工具调用
        topologyNodes.add(Map.of(
            "id", "node-mcp-sql-exec",
            "name", "MCP 协议受限沙箱执行动作",
            "type", "MCP_TOOL_CALL",
            "risk", estimatedRisk > 0.5 ? "HIGH" : "LOW",
            "description", "通过 MCP 开放协议连接企业数据库/微服务执行生产变更"
        ));

        // Step 5: 不可篡改存证
        topologyNodes.add(Map.of(
            "id", "node-audit-ledger",
            "name", "防篡改加密审计凭证存证",
            "type", "SQL_EXECUTOR",
            "risk", "LOW",
            "description", "生成 SHA-256 不可抵赖数字存证收据并归档持久化"
        ));

        String topologyJson;
        try {
            topologyJson = objectMapper.writeValueAsString(topologyNodes);
        } catch (Exception e) {
            topologyJson = "[]";
        }

        // 执行 Tarjan SCC 拓扑编译
        List<String> nodeIds = new ArrayList<>();
        Map<String, List<String>> deps = new HashMap<>();
        for (int i = 0; i < topologyNodes.size(); i++) {
            String nid = (String) topologyNodes.get(i).get("id");
            nodeIds.add(nid);
            if (i > 0) {
                deps.put(nid, List.of((String) topologyNodes.get(i - 1).get("id")));
            } else {
                deps.put(nid, Collections.emptyList());
            }
        }
        TarjanSccService.TarjanReport tarjanReport = tarjanSccService.analyzeGraph(nodeIds, deps);

        // 持久化到 MySQL 8.0 / H2 workflow_definition
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(dynamicDefId);
        def.setName(dynamicName);
        def.setDescription("根据业务意图由 Planner 自动解构编排，属于 " + domain + " 业务域，预估风险 " + String.format("%.2f", estimatedRisk));
        def.setVersion("v1.0.0-dyn");
        def.setTopologyJson(topologyJson);
        def.setCreatedAt(LocalDateTime.now());
        def.setUpdatedAt(LocalDateTime.now());
        definitionRepository.save(def);

        return new PlanResult(def, tarjanReport, domain, estimatedRisk, matchedTools, matchedKnowledge);
    }
}
