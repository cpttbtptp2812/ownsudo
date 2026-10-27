package com.ownsudo.service;

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

import java.io.IOException;
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

    // Map: instanceId -> List of active SseEmitters
    private final Map<String, List<SseEmitter>> emittersMap = new ConcurrentHashMap<>();

    public SseEmitter subscribeStream(String instanceId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        emittersMap.computeIfAbsent(instanceId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(instanceId, emitter));
        emitter.onTimeout(() -> removeEmitter(instanceId, emitter));
        emitter.onError(e -> removeEmitter(instanceId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data(new WorkflowEventMessage("INIT", instanceId, null, "CONNECTED", "SSE Stream Established")));
        } catch (IOException e) {
            emitter.complete();
        }

        return emitter;
    }

    private void removeEmitter(String instanceId, SseEmitter emitter) {
        List<SseEmitter> list = emittersMap.get(instanceId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) emittersMap.remove(instanceId);
        }
    }

    public void broadcastEvent(String instanceId, WorkflowEventMessage msg) {
        List<SseEmitter> list = emittersMap.get(instanceId);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(msg.getEventType()).data(msg));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        list.removeAll(deadEmitters);
    }

    /**
     * Start executing DAG workflow asynchronously
     */
    public WorkflowInstance startWorkflow(String workflowId, String triggeredBy, String inputParams) {
        if (workflowId == null || workflowId.isBlank()) {
            workflowId = "wf-settlement-migration";
        }
        if (triggeredBy == null || triggeredBy.isBlank()) {
            triggeredBy = "admin_sec";
        }

        String instanceId = "wf-inst-" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(instanceId);
        instance.setWorkflowId(workflowId);
        instance.setStatus("RUNNING");
        instance.setTriggeredBy(triggeredBy);
        instance.setStartedAt(LocalDateTime.now());
        instance.setCurrentNodeId("node-ingest-spec");
        instance.setInputParams(inputParams);
        instanceRepository.save(instance);

        executePipelineAsync(instanceId, workflowId);
        return instance;
    }

    @Async
    public void executePipelineAsync(String instanceId, String workflowId) {
        try {
            broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_STARTED", instanceId, null, "RUNNING", "Workflow Instance Initialized"));

            // Step 1: node-ingest-spec
            executeStep(instanceId, "node-ingest-spec", "交易规范解析与协议合规自检", "POLICY_VERIFICATION", 320, () -> {
                return Map.of(
                    "complianceCheck", "PASSED_ISO20022",
                    "sourceAccount", "6225 **** **** 9081",
                    "destinationBank", "ICBKCNSH",
                    "amount", "50,000,000.00 CNY",
                    "rulesCount", 14
                );
            });

            // Step 2: node-risk-analysis
            executeStep(instanceId, "node-risk-analysis", "大模型风险沙箱与意图影响推演", "LLM_REASONING", 850, () -> {
                return Map.of(
                    "riskScore", 0.94,
                    "riskLevel", "CRITICAL_P0",
                    "reasons", List.of(
                        "单笔划转金额 > 10,000,000 触发央行反洗钱大额重点监控",
                        "目标结算清算专户涉及多云异构集群跨网段调度",
                        "涉及核心账务主库不可逆借贷结转，强制触发两阶段提交 (2PC)"
                    ),
                    "model", "deepseek-r1-reasoner",
                    "policyTriggered", "POLICY_ZERO_TRUST_THRESHOLD"
                );
            });

            // Step 3: node-2pc-gate (Zero-Trust Gate - Suspend here!)
            suspendForApprovalGate(instanceId, "node-2pc-gate", "零信任 2PC 人机双签闸口 (Zero-Trust Gate)");

        } catch (Exception e) {
            log.error("Workflow execution error for instance " + instanceId, e);
            markInstanceFailed(instanceId, e.getMessage());
        }
    }

    private void executeStep(String instanceId, String nodeId, String nodeName, String nodeType, long latencyMs, StepSupplier supplier) throws Exception {
        WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
        instance.setCurrentNodeId(nodeId);
        instanceRepository.save(instance);

        WorkflowEventMessage runningMsg = new WorkflowEventMessage("NODE_RUNNING", instanceId, nodeId, "RUNNING", "Node started");
        runningMsg.setNodeName(nodeName);
        runningMsg.setNodeType(nodeType);
        broadcastEvent(instanceId, runningMsg);

        Thread.sleep(latencyMs);

        Object result = supplier.execute();
        String output = result instanceof String ? (String) result : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);

        NodeExecution node = new NodeExecution();
        node.setId("exec-" + UUID.randomUUID().toString().substring(0, 8));
        node.setInstanceId(instanceId);
        node.setNodeId(nodeId);
        node.setNodeName(nodeName);
        node.setNodeType(nodeType);
        node.setStatus("SUCCESS");
        node.setOutputPayload(output);
        node.setStartedAt(LocalDateTime.now().minusNanos(latencyMs * 1_000_000));
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

        NodeExecution node = new NodeExecution();
        node.setId("exec-" + UUID.randomUUID().toString().substring(0, 8));
        node.setInstanceId(instanceId);
        node.setNodeId(nodeId);
        node.setNodeName(nodeName);
        node.setNodeType("HUMAN_APPROVAL_2PC");
        node.setStatus("BLOCKED");
        node.setStartedAt(LocalDateTime.now());
        nodeExecutionRepository.save(node);

        String ticketId = "tkt-" + System.currentTimeMillis();
        ApprovalTicket ticket = new ApprovalTicket();
        ticket.setId(ticketId);
        ticket.setInstanceId(instanceId);
        ticket.setNodeId(nodeId);
        ticket.setPolicyLevel("P0_CRITICAL");
        ticket.setRiskScore(0.94);
        ticket.setOperationSummary("【P0 紧急授权】清算转账金额 ¥50,000,000.00 CNY 至清算保证金池，并执行 MySQL agent_db 表级数据回滚防护索引重构。");
        ticket.setStatus("PENDING");
        approvalTicketRepository.save(ticket);

        WorkflowEventMessage gateMsg = new WorkflowEventMessage("TICKET_REQUIRED", instanceId, nodeId, "SUSPENDED_APPROVAL", ticket);
        gateMsg.setNodeName(nodeName);
        gateMsg.setNodeType("HUMAN_APPROVAL_2PC");
        broadcastEvent(instanceId, gateMsg);

        log.info("Workflow instance {} suspended at 2PC gate with ticket {}", instanceId, ticketId);
    }

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

        String sigInput = ticketId + ":" + req.getOperatorId() + ":" + req.getDecision() + ":" + System.currentTimeMillis();
        ticket.setSignatureHash(sha256(sigInput));
        approvalTicketRepository.save(ticket);

        if ("APPROVED".equalsIgnoreCase(req.getDecision())) {
            Optional<NodeExecution> optNode = nodeExecutionRepository.findByInstanceIdAndNodeId(instanceId, ticket.getNodeId());
            optNode.ifPresent(n -> {
                n.setStatus("SUCCESS");
                n.setOutputPayload("{\"signature\":\"" + ticket.getSignatureHash() + "\",\"operator\":\"" + req.getOperatorId() + "\",\"status\":\"APPROVED\"}");
                n.setEndedAt(LocalDateTime.now());
                nodeExecutionRepository.save(n);
            });

            instance.setStatus("RUNNING");
            instanceRepository.save(instance);

            broadcastEvent(instanceId, new WorkflowEventMessage("TICKET_APPROVED", instanceId, ticket.getNodeId(), "APPROVED", ticket));
            resumePipelineAsync(instanceId);

        } else {
            // Saga Rollback compensation on Rejection
            Optional<NodeExecution> optNode = nodeExecutionRepository.findByInstanceIdAndNodeId(instanceId, ticket.getNodeId());
            optNode.ifPresent(n -> {
                n.setStatus("FAILED");
                n.setOutputPayload("{\"rejectionReason\":\"" + req.getAuditReason() + "\",\"sagaRollback\":\"TRIGGERED\"}");
                n.setEndedAt(LocalDateTime.now());
                nodeExecutionRepository.save(n);
            });

            // Insert Saga Compensation Record
            NodeExecution compNode = new NodeExecution();
            compNode.setId("exec-comp-" + UUID.randomUUID().toString().substring(0, 6));
            compNode.setInstanceId(instanceId);
            compNode.setNodeId("saga-compensation-rollback");
            compNode.setNodeName("Saga 逆向事务补偿回滚");
            compNode.setNodeType("COMPENSATION_ACTION");
            compNode.setStatus("COMPENSATED");
            compNode.setOutputPayload("{\"releasedHoldAmount\":\"¥50,000,000.00\",\"status\":\"ROLLBACK_SUCCESS\"}");
            compNode.setStartedAt(LocalDateTime.now());
            compNode.setEndedAt(LocalDateTime.now());
            nodeExecutionRepository.save(compNode);

            instance.setStatus("REJECTED_ROLLED_BACK");
            instance.setFinishedAt(LocalDateTime.now());
            instanceRepository.save(instance);

            broadcastEvent(instanceId, new WorkflowEventMessage("TICKET_REJECTED", instanceId, ticket.getNodeId(), "REJECTED_ROLLED_BACK", ticket));
        }

        return ticket;
    }

    @Async
    public void resumePipelineAsync(String instanceId) {
        try {
            // Step 4: node-mcp-sql-exec
            executeStep(instanceId, "node-mcp-sql-exec", "MCP 协议分布式数据库执行器", "MCP_TOOL_CALL", 520, () -> {
                return Map.of(
                    "affectedRows", 1,
                    "targetHost", "43.142.74.224:3306",
                    "executedSql", "UPDATE agent_db.account_balance SET balance = balance - 50000000.00 WHERE account_id = 'ACC_CORP_01';",
                    "mcpStatus", "200_OK",
                    "isolation", "SERIALIZABLE"
                );
            });

            // Step 5: node-audit-ledger
            executeStep(instanceId, "node-audit-ledger", "防篡改加密审计凭证存证", "SQL_EXECUTOR", 220, () -> {
                return Map.of(
                    "blockHeight", 10925,
                    "ledgerHash", "0x5b6487247985d9d7949f9a04fd038381929b5dc406ca1939bebaae0cbce5b669",
                    "auditProof", "SEC_COMPLIANT_RECORDED",
                    "merkleRoot", "0xedc00da816c664bd9c1bdb5d3a1e7b87"
                );
            });

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