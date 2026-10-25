package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.CommitApprovalRequest;
import com.ownsudo.dto.WorkflowEventMessage;
import com.ownsudo.entity.ApprovalTicket;
import com.ownsudo.entity.NodeExecution;
import com.ownsudo.entity.WorkflowInstance;
import com.ownsudo.repository.ApprovalTicketRepository;
import com.ownsudo.repository.NodeExecutionRepository;
import com.ownsudo.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class WorkflowEngineService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngineService.class);

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;

    @Autowired
    private ApprovalTicketRepository approvalTicketRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // SSE emitter registry keyed by instanceId
    private final Map<String, List<SseEmitter>> emitterMap = new ConcurrentHashMap<>();

    public SseEmitter subscribeStream(String instanceId) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout
        emitterMap.computeIfAbsent(instanceId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(instanceId, emitter));
        emitter.onTimeout(() -> removeEmitter(instanceId, emitter));
        emitter.onError((e) -> removeEmitter(instanceId, emitter));

        // Send initial heartbeat
        try {
            emitter.send(SseEmitter.event().name("INIT").data(new WorkflowEventMessage("INIT", instanceId, null, "CONNECTED", "Stream initialized")));
        } catch (Exception ignored) {}

        return emitter;
    }

    private void removeEmitter(String instanceId, SseEmitter emitter) {
        List<SseEmitter> list = emitterMap.get(instanceId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitterMap.remove(instanceId);
            }
        }
    }

    public void broadcastEvent(String instanceId, WorkflowEventMessage event) {
        List<SseEmitter> list = emitterMap.get(instanceId);
        if (list != null && !list.isEmpty()) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.getEventType())
                            .data(event));
                } catch (Exception e) {
                    emitter.complete();
                }
            }
        }
    }

    /**
     * Start a new workflow instance execution
     */
    public WorkflowInstance startWorkflow(String workflowId, String triggeredBy, String inputParams) {
        String instanceId = "inst-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4);
        
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(instanceId);
        instance.setWorkflowId(workflowId != null ? workflowId : "wf-settlement-migration");
        instance.setStatus("RUNNING");
        instance.setTriggeredBy(triggeredBy != null ? triggeredBy : "admin_sec");
        instance.setContextData(inputParams != null ? inputParams : "{\"txAmount\": 15000000.00, \"currency\": \"CNY\", \"targetDb\": \"mysql://43.142.74.224/agent_db\"}");
        instance.setCurrentNodeId("node-ingest-spec");
        instance.setStartedAt(LocalDateTime.now());

        instanceRepository.save(instance);

        // Broadcast start event
        broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_STARTED", instanceId, null, "RUNNING", instance));

        // Trigger asynchronous DAG progression
        executePipelineAsync(instanceId);

        return instance;
    }

    @Async
    public void executePipelineAsync(String instanceId) {
        try {
            // Step 1: node-ingest-spec
            executeStep(instanceId, "node-ingest-spec", "交易规范解析与协议合规自检", "POLICY_VERIFICATION", 400, () -> {
                return Map.of("parsedFields", 14, "complianceRule", "ISO20022_PASS", "ddlTarget", "agent_db.workflow_instance");
            });

            // Step 2: node-risk-analysis
            executeStep(instanceId, "node-risk-analysis", "大模型风险沙箱与意图影响推演", "LLM_REASONING", 750, () -> {
                return Map.of(
                    "riskScore", 0.94,
                    "threatCategory", "HIGH_RISK_FINANCIAL_SETTLEMENT",
                    "reasoningSummary", "检测到单笔超 10,000,000.00 CNY 的资产重分配操作，触发企业 P0 级强制 2PC 双签策略。"
                );
            });

            // Step 3: node-2pc-gate -> Zero-Trust Human Approval Suspension
            suspendForApprovalGate(instanceId, "node-2pc-gate", "零信任 2PC 人机双签闸口 (Zero-Trust Gate)");

        } catch (Exception e) {
            log.error("Pipeline async execution failed for instance " + instanceId, e);
            markInstanceFailed(instanceId, e.getMessage());
        }
    }

    private void executeStep(String instanceId, String nodeId, String nodeName, String nodeType, long latencyMs, StepSupplier supplier) throws Exception {
        WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
        instance.setCurrentNodeId(nodeId);
        instanceRepository.save(instance);

        NodeExecution node = new NodeExecution();
        node.setId("exec-" + UUID.randomUUID().toString().substring(0, 8));
        node.setInstanceId(instanceId);
        node.setNodeId(nodeId);
        node.setNodeName(nodeName);
        node.setNodeType(nodeType);
        node.setStatus("RUNNING");
        node.setStartedAt(LocalDateTime.now());
        nodeExecutionRepository.save(node);

        WorkflowEventMessage runningMsg = new WorkflowEventMessage("NODE_RUNNING", instanceId, nodeId, "RUNNING", Map.of("nodeName", nodeName));
        runningMsg.setNodeName(nodeName);
        runningMsg.setNodeType(nodeType);
        broadcastEvent(instanceId, runningMsg);

        Thread.sleep(latencyMs);

        Object output = supplier.execute();
        node.setStatus("SUCCESS");
        node.setOutputPayload(objectMapper.writeValueAsString(output));
        node.setLatencyMs(latencyMs);
        node.setCostTokens((int) (Math.random() * 800 + 400));
        node.setEndedAt(LocalDateTime.now());
        nodeExecutionRepository.save(node);

        WorkflowEventMessage completeMsg = new WorkflowEventMessage("NODE_COMPLETED", instanceId, nodeId, "SUCCESS", output);
        completeMsg.setNodeName(nodeName);
        completeMsg.setNodeType(nodeType);
        broadcastEvent(instanceId, completeMsg);
    }

    private void suspendForApprovalGate(String instanceId, String nodeId, String nodeName) throws Exception {
        WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
        instance.setStatus("SUSPENDED_APPROVAL");
        instance.setCurrentNodeId(nodeId);
        instanceRepository.save(instance);

        // Create or update NodeExecution
        NodeExecution node = new NodeExecution();
        node.setId("exec-" + UUID.randomUUID().toString().substring(0, 8));
        node.setInstanceId(instanceId);
        node.setNodeId(nodeId);
        node.setNodeName(nodeName);
        node.setNodeType("HUMAN_APPROVAL_2PC");
        node.setStatus("BLOCKED");
        node.setStartedAt(LocalDateTime.now());
        nodeExecutionRepository.save(node);

        // Generate Zero-Trust 2PC Approval Ticket
        String ticketId = "tkt-" + System.currentTimeMillis();
        ApprovalTicket ticket = new ApprovalTicket();
        ticket.setId(ticketId);
        ticket.setInstanceId(instanceId);
        ticket.setNodeId(nodeId);
        ticket.setPolicyLevel("P0_CRITICAL");
        ticket.setRiskScore(0.94);
        ticket.setOperationSummary("【P0 紧急授权】清算转账金额 ¥15,000,000.00 CNY 至清算保证金池，并执行 MySQL agent_db 表级数据回滚防护索引重构。");
        ticket.setStatus("PENDING");
        approvalTicketRepository.save(ticket);

        // Broadcast Gate Notification
        WorkflowEventMessage gateMsg = new WorkflowEventMessage("TICKET_REQUIRED", instanceId, nodeId, "SUSPENDED_APPROVAL", ticket);
        gateMsg.setNodeName(nodeName);
        gateMsg.setNodeType("HUMAN_APPROVAL_2PC");
        broadcastEvent(instanceId, gateMsg);

        log.info("Workflow instance {} suspended at 2PC gate with ticket {}", instanceId, ticketId);
    }

    /**
     * Resume after human commits decision on 2PC ticket
     */
    public ApprovalTicket commitApproval(String ticketId, CommitApprovalRequest req) {
        ApprovalTicket ticket = approvalTicketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        if (!"PENDING".equalsIgnoreCase(ticket.getStatus())) {
            throw new IllegalStateException("Ticket already processed: " + ticket.getStatus());
        }

        String instanceId = ticket.getInstanceId();
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        ticket.setOperatorId(req.getOperatorId());
        ticket.setStatus(req.getDecision());
        ticket.setAuditReason(req.getAuditReason());
        ticket.setDecidedAt(LocalDateTime.now());

        // Generate SHA-256 digital signature
        String sigInput = ticketId + ":" + req.getOperatorId() + ":" + req.getDecision() + ":" + System.currentTimeMillis();
        ticket.setSignatureHash(sha256(sigInput));
        approvalTicketRepository.save(ticket);

        if ("APPROVED".equalsIgnoreCase(req.getDecision())) {
            // Update node status
            Optional<NodeExecution> optNode = nodeExecutionRepository.findByInstanceIdAndNodeId(instanceId, ticket.getNodeId());
            optNode.ifPresent(n -> {
                n.setStatus("SUCCESS");
                n.setOutputPayload("{\"signature\":\"" + ticket.getSignatureHash() + "\",\"operator\":\"" + req.getOperatorId() + "\"}");
                n.setEndedAt(LocalDateTime.now());
                nodeExecutionRepository.save(n);
            });

            instance.setStatus("RUNNING");
            instanceRepository.save(instance);

            broadcastEvent(instanceId, new WorkflowEventMessage("TICKET_APPROVED", instanceId, ticket.getNodeId(), "APPROVED", ticket));

            // Continue downstream nodes asynchronously
            resumePipelineAsync(instanceId);

        } else {
            // Rejected
            Optional<NodeExecution> optNode = nodeExecutionRepository.findByInstanceIdAndNodeId(instanceId, ticket.getNodeId());
            optNode.ifPresent(n -> {
                n.setStatus("FAILED");
                n.setOutputPayload("{\"rejectionReason\":\"" + req.getAuditReason() + "\"}");
                n.setEndedAt(LocalDateTime.now());
                nodeExecutionRepository.save(n);
            });

            markInstanceFailed(instanceId, "2PC Human Gate Rejected by " + req.getOperatorId());
            broadcastEvent(instanceId, new WorkflowEventMessage("TICKET_REJECTED", instanceId, ticket.getNodeId(), "REJECTED", ticket));
        }

        return ticket;
    }

    @Async
    public void resumePipelineAsync(String instanceId) {
        try {
            // Step 4: node-mcp-sql-exec
            executeStep(instanceId, "node-mcp-sql-exec", "MCP 协议分布式数据库执行器", "MCP_TOOL_CALL", 920, () -> {
                return Map.of(
                    "affectedRows", 1,
                    "targetHost", "43.142.74.224:3306",
                    "executedSql", "UPDATE agent_db.account_balance SET balance = balance - 15000000.00 WHERE account_id = 'ACC_CORP_01';",
                    "mcpStatus", "200_OK"
                );
            });

            // Step 5: node-audit-ledger
            executeStep(instanceId, "node-audit-ledger", "防篡改加密审计凭证存证", "SQL_EXECUTOR", 350, () -> {
                return Map.of(
                    "blockHeight", 894102,
                    "ledgerHash", "0x" + UUID.randomUUID().toString().replace("-", ""),
                    "auditProof", "SEC_COMPLIANT_RECORDED"
                );
            });

            // Finish pipeline
            WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
            instance.setStatus("COMPLETED");
            instance.setFinishedAt(LocalDateTime.now());
            instanceRepository.save(instance);

            broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_COMPLETED", instanceId, null, "COMPLETED", instance));

        } catch (Exception e) {
            log.error("Resume pipeline failed for " + instanceId, e);
            markInstanceFailed(instanceId, e.getMessage());
        }
    }

    private void markInstanceFailed(String instanceId, String error) {
        instanceRepository.findById(instanceId).ifPresent(inst -> {
            inst.setStatus("FAILED");
            inst.setFinishedAt(LocalDateTime.now());
            instanceRepository.save(inst);
            broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_FAILED", instanceId, inst.getCurrentNodeId(), "FAILED", error));
        });
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    @FunctionalInterface
    interface StepSupplier {
        Object execute() throws Exception;
    }
}
