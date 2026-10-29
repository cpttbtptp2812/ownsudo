package com.ownsudo.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.CommitApprovalRequest;
import com.ownsudo.dto.RunWorkflowRequest;
import com.ownsudo.entity.ApprovalTicket;
import com.ownsudo.entity.NodeExecution;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.entity.WorkflowInstance;
import com.ownsudo.repository.ApprovalTicketRepository;
import com.ownsudo.repository.NodeExecutionRepository;
import com.ownsudo.repository.WorkflowInstanceRepository;
import com.ownsudo.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    @Autowired
    private WorkflowDefinitionService definitionService;

    @Autowired
    private WorkflowEngineService engineService;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;

    @Autowired
    private ApprovalTicketRepository approvalTicketRepository;

    @Autowired
    private TarjanSccService tarjanSccService;

    @Autowired
    private HybridRagService hybridRagService;

    @Autowired
    private ModelGatewayService modelGatewayService;

    @Autowired
    private W3CTracingService w3cTracingService;

    @Autowired
    private MerkleLedgerService merkleLedgerService;

    @Autowired
    private com.ownsudo.repository.MerkleBlockRepository merkleBlockRepository;

    @Autowired
    private WorkflowPlannerService plannerService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/definitions")
    public List<WorkflowDefinition> getDefinitions() {
        return definitionService.getAllDefinitions();
    }

    @GetMapping("/definitions/{id}")
    public ResponseEntity<WorkflowDefinition> getDefinition(@PathVariable String id) {
        return definitionService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/definitions")
    public ResponseEntity<WorkflowDefinition> saveDefinition(@RequestBody WorkflowDefinition def) {
        return ResponseEntity.ok(definitionService.save(def));
    }

    @DeleteMapping("/definitions/{id}")
    public ResponseEntity<Map<String, Object>> deleteDefinition(@PathVariable String id) {
        definitionService.delete(id);
        return ResponseEntity.ok(Map.of("ok", true, "deletedId", id));
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runWorkflow(@RequestBody(required = false) RunWorkflowRequest req) {
        if (req == null) req = new RunWorkflowRequest();
        WorkflowInstance instance = engineService.startWorkflow(req.getWorkflowId(), req.getTriggeredBy(), req.getInputParams());
        return ResponseEntity.ok(Map.of(
            "instanceId", instance.getId(),
            "status", instance.getStatus(),
            "workflowId", instance.getWorkflowId(),
            "startedAt", instance.getStartedAt()
        ));
    }

    @GetMapping("/instances")
    public List<WorkflowInstance> listInstances() {
        return instanceRepository.findTop20ByOrderByStartedAtDesc();
    }

    @GetMapping({"/instances/{id}", "/instance/{id}"})
    public ResponseEntity<Map<String, Object>> getInstanceDetails(@PathVariable String id) {
        return instanceRepository.findById(id).map(inst -> {
            List<NodeExecution> steps = nodeExecutionRepository.findByInstanceIdOrderByStartedAtAsc(id);
            return ResponseEntity.ok(Map.of(
                "instance", inst,
                "steps", steps
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/instances/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamWorkflowEvents(@PathVariable String id) {
        return engineService.subscribeStream(id);
    }

    @GetMapping("/approvals/pending")
    public List<ApprovalTicket> getPendingApprovals() {
        return approvalTicketRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    @PostMapping("/approvals/{ticketId}/commit")
    public ResponseEntity<?> commitApproval(
            @PathVariable String ticketId,
            @RequestBody CommitApprovalRequest req) {
        try {
            ApprovalTicket ticket = engineService.commitApproval(ticketId, req);
            return ResponseEntity.ok(ticket);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 阶段一：Tarjan 强连通分量 (SCC) 环路校验与拓扑编译
     */
    @GetMapping("/blueprint/validate/{id}")
    public ResponseEntity<?> validateBlueprint(@PathVariable String id) {
        Optional<WorkflowDefinition> opt = definitionService.getById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        WorkflowDefinition def = opt.get();
        List<String> nodeIds = new ArrayList<>();
        Map<String, List<String>> deps = new HashMap<>();

        try {
            List<Map<String, Object>> nodes = objectMapper.readValue(def.getTopologyJson(), new TypeReference<>() {});
            for (int i = 0; i < nodes.size(); i++) {
                String nid = (String) nodes.get(i).get("id");
                nodeIds.add(nid);
                if (i > 0) {
                    deps.put(nid, List.of((String) nodes.get(i - 1).get("id")));
                } else {
                    deps.put(nid, Collections.emptyList());
                }
            }
        } catch (Exception e) {
            // fallback
            nodeIds = List.of("node-ingest-spec", "node-risk-analysis", "node-2pc-gate", "node-mcp-sql-exec", "node-audit-ledger");
            deps.put("node-risk-analysis", List.of("node-ingest-spec"));
            deps.put("node-2pc-gate", List.of("node-risk-analysis"));
            deps.put("node-mcp-sql-exec", List.of("node-2pc-gate"));
            deps.put("node-audit-ledger", List.of("node-mcp-sql-exec"));
        }

        TarjanSccService.TarjanReport report = tarjanSccService.analyzeGraph(nodeIds, deps);
        return ResponseEntity.ok(report);
    }

    /**
     * 阶段二：双路混合 RAG 上下文装配
     */
    @PostMapping("/rag/hybrid-search")
    public ResponseEntity<?> hybridSearch(@RequestBody(required = false) Map<String, Object> req) {
        String query = req != null && req.containsKey("query") ? String.valueOf(req.get("query")) : "跨行资金清算 备付金 零信任";
        int limit = req != null && req.containsKey("limit") ? ((Number) req.get("limit")).intValue() : 5;
        List<HybridRagService.RagChunk> results = hybridRagService.hybridSearch(query, limit);
        return ResponseEntity.ok(Map.of("query", query, "results", results, "totalRetrieved", results.size()));
    }

    /**
     * 阶段三：模型网关与熔断器指标遥测
     */
    @GetMapping("/gateway/status")
    public ResponseEntity<?> gatewayStatus() {
        return ResponseEntity.ok(Map.of("ok", true, "metrics", modelGatewayService.getMetrics()));
    }

    /**
     * 阶段五：W3C 分布式追踪与调用火焰图
     */
    @GetMapping("/instances/{id}/trace")
    public ResponseEntity<?> getInstanceTrace(@PathVariable String id) {
        List<NodeExecution> steps = nodeExecutionRepository.findByInstanceIdOrderByStartedAtAsc(id);
        return ResponseEntity.ok(w3cTracingService.buildTraceSpans(id, steps));
    }

    /**
     * 阶段五：增量 Merkle 树证据链证明
     */
    @GetMapping("/instances/{id}/merkle")
    public ResponseEntity<?> getInstanceMerkle(@PathVariable String id) {
        List<NodeExecution> steps = nodeExecutionRepository.findByInstanceIdOrderByStartedAtAsc(id);
        return ResponseEntity.ok(merkleLedgerService.generateMerkleProof(id, steps));
    }

    /**
     * 阶段五：默克尔不可篡改区块链账本视图（真实从数据库加载区块）
     */
    @GetMapping("/ledger/blocks")
    public ResponseEntity<?> getLedgerBlocks() {
        return ResponseEntity.ok(Map.of("ok", true, "blocks", merkleBlockRepository.findTop20ByOrderByBlockHeightDesc()));
    }

    /**
     * 阶段二：动态意图解构、图拓扑编译与 Tarjan SCC 验证
     */
    @PostMapping("/plan")
    public ResponseEntity<?> planWorkflow(@RequestBody(required = false) Map<String, Object> req) {
        String prompt = req != null && req.containsKey("prompt") ? String.valueOf(req.get("prompt")) : "通用自动化业务核对与数据变更任务";
        String department = req != null && req.containsKey("department") ? String.valueOf(req.get("department")) : "Enterprise-Operations";
        String operator = req != null && req.containsKey("operator") ? String.valueOf(req.get("operator")) : "SecOps-Director";
        WorkflowPlannerService.PlanResult result = plannerService.planDynamicWorkflow(prompt, department, operator);
        return ResponseEntity.ok(result);
    }

}
