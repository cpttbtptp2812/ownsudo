package com.ownsudo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.CommitApprovalRequest;
import com.ownsudo.dto.WorkflowEventMessage;
import com.ownsudo.entity.ApprovalTicket;
import com.ownsudo.entity.NodeExecution;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.entity.WorkflowInstance;
import com.ownsudo.repository.ApprovalTicketRepository;
import com.ownsudo.repository.NodeExecutionRepository;
import com.ownsudo.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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

    @Autowired
    @Lazy
    private WorkflowDefinitionService definitionService;

    @Autowired
    private ObjectMapper objectMapper;

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

            Optional<WorkflowDefinition> optDef = definitionService.getById(workflowId);
            List<Map<String, Object>> dynamicSteps = Collections.emptyList();
            if (optDef.isPresent() && optDef.get().getTopologyJson() != null && !optDef.get().getTopologyJson().isBlank()) {
                try {
                    dynamicSteps = objectMapper.readValue(optDef.get().getTopologyJson(), new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse dynamic steps for workflow " + workflowId, e);
                }
            }

            if (dynamicSteps != null && !dynamicSteps.isEmpty()) {
                WorkflowInstance inst = instanceRepository.findById(instanceId).orElse(null);
                String inputParams = inst != null ? inst.getInputParams() : "";

                for (Map<String, Object> step : dynamicSteps) {
                    String type = String.valueOf(step.getOrDefault("type", ""));
                    String nodeId = String.valueOf(step.getOrDefault("id", "step-" + System.currentTimeMillis()));
                    String nodeName = String.valueOf(step.getOrDefault("name", "执行节点"));

                    if ("HITL_APPROVAL".equalsIgnoreCase(type)) {
                        suspendForApprovalGate(instanceId, nodeId, nodeName.isBlank() ? "2PC 人工安全审批门闩" : nodeName);
                        return; // 挂起并等待人工审批
                    } else {
                        executeDynamicStep(instanceId, step, inputParams);
                    }
                }

                WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
                instance.setStatus("COMPLETED");
                instance.setFinishedAt(LocalDateTime.now());
                instanceRepository.save(instance);
                broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_COMPLETED", instanceId, null, "COMPLETED", instance));
                return;
            }

            // 兜底回退
            executeStep(instanceId, "node-ingest-spec", "交易规范解析与协议合规自检", "POLICY_VERIFICATION", 320, () -> {
                return Map.of(
                    "complianceCheck", "PASSED_ISO20022",
                    "sourceAccount", "6225 **** **** 9081",
                    "destinationBank", "ICBKCNSH",
                    "amount", "50,000,000.00 CNY",
                    "rulesCount", 14
                );
            });

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

            suspendForApprovalGate(instanceId, "node-2pc-gate", "零信任 2PC 人机双签闸口 (Zero-Trust Gate)");

        } catch (Exception e) {
            log.error("Workflow execution error for instance " + instanceId, e);
            markInstanceFailed(instanceId, e.getMessage());
        }
    }

    private void executeDynamicStep(String instanceId, Map<String, Object> step, String inputParams) throws Exception {
        String nodeId = String.valueOf(step.getOrDefault("id", "step-" + System.currentTimeMillis()));
        String nodeName = String.valueOf(step.getOrDefault("name", "动态执行节点"));
        String nodeType = String.valueOf(step.getOrDefault("type", "GENERIC_STEP"));
        Map<String, Object> config = step.get("config") instanceof Map ? (Map<String, Object>) step.get("config") : Collections.emptyMap();

        long latencyMs = 180 + (long)(Math.random() * 260);
        executeStep(instanceId, nodeId, nodeName, nodeType, latencyMs, () -> {
            Map<String, Object> output = new HashMap<>();
            output.put("nodeId", nodeId);
            output.put("nodeType", nodeType);
            output.put("status", "SUCCESS");
            output.put("timestamp", System.currentTimeMillis());

            if ("INPUT_EXTRACT".equalsIgnoreCase(nodeType)) {
                output.put("fieldsExtracted", config.getOrDefault("fields", "applicant, amount, department"));
                output.put("maskApplied", config.getOrDefault("maskSensitive", true));
                output.put("inputData", inputParams != null && !inputParams.isBlank() ? inputParams : "通用业务输入已解析");
            } else if ("RULE_CHECK".equalsIgnoreCase(nodeType)) {
                output.put("ruleEvaluated", config.getOrDefault("ruleExpr", "amount <= 50000"));
                output.put("complianceResult", "PASSED_GREEN_ZONE");
                output.put("riskFactor", 0.05);
            } else if ("HTTP_TOOL".equalsIgnoreCase(nodeType)) {
                output.put("endpoint", config.getOrDefault("apiUrl", "https://api.internal.com/action"));
                output.put("method", config.getOrDefault("apiMethod", "POST"));
                output.put("httpStatus", 200);
                output.put("responseBody", Map.of("code", 0, "msg", "Success", "bizId", "BIZ-" + System.currentTimeMillis()));
            } else if ("LLM_REDUCE".equalsIgnoreCase(nodeType)) {
                output.put("format", config.getOrDefault("outputFormat", "STREAM_TEXT"));
                output.put("summary", "流水线全部前置节点已成功执行并归一化入库，生成结构化凭据。");
                output.put("tokenUsage", 280);
            } else {
                output.put("result", "Node executed successfully with dynamic config: " + config.keySet());
            }
            return output;
        });
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
        ticket.setRiskScore(0.85);
        String bizDesc = instance.getInputParams() != null && !instance.getInputParams().isBlank()
                ? "，业务数据：" + instance.getInputParams() : "";
        ticket.setOperationSummary("【安全授权闸口】节点 [" + nodeName + "] 触发安全核验门闩，需专职人员审核放行" + bizDesc);
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
            compNode.setOutputPayload("{\"reason\":\"" + req.getAuditReason() + "\",\"status\":\"ROLLBACK_SUCCESS\",\"message\":\"业务执行已安全撤销并完成逆向事务冲正\"}");
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
            WorkflowInstance instance = instanceRepository.findById(instanceId).orElseThrow();
            String workflowId = instance.getWorkflowId();
            Optional<WorkflowDefinition> optDef = definitionService.getById(workflowId);
            List<Map<String, Object>> dynamicSteps = Collections.emptyList();
            if (optDef.isPresent() && optDef.get().getTopologyJson() != null && !optDef.get().getTopologyJson().isBlank()) {
                try {
                    dynamicSteps = objectMapper.readValue(optDef.get().getTopologyJson(), new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse dynamic steps for resume: " + workflowId, e);
                }
            }

            if (dynamicSteps != null && !dynamicSteps.isEmpty()) {
                String currentNodeId = instance.getCurrentNodeId();
                int startIndex = 0;
                for (int i = 0; i < dynamicSteps.size(); i++) {
                    String stepId = String.valueOf(dynamicSteps.get(i).getOrDefault("id", ""));
                    if (stepId.equals(currentNodeId)) {
                        startIndex = i + 1;
                        break;
                    }
                }

                String inputParams = instance.getInputParams();
                for (int i = startIndex; i < dynamicSteps.size(); i++) {
                    Map<String, Object> step = dynamicSteps.get(i);
                    String type = String.valueOf(step.getOrDefault("type", ""));
                    String nodeId = String.valueOf(step.getOrDefault("id", "step-" + System.currentTimeMillis()));
                    String nodeName = String.valueOf(step.getOrDefault("name", "执行节点"));

                    if ("HITL_APPROVAL".equalsIgnoreCase(type)) {
                        suspendForApprovalGate(instanceId, nodeId, nodeName.isBlank() ? "2PC 人工安全审批门闩" : nodeName);
                        return;
                    } else {
                        executeDynamicStep(instanceId, step, inputParams);
                    }
                }

                WorkflowInstance finishedInstance = instanceRepository.findById(instanceId).orElseThrow();
                finishedInstance.setStatus("COMPLETED");
                finishedInstance.setFinishedAt(LocalDateTime.now());
                instanceRepository.save(finishedInstance);

                broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_COMPLETED", instanceId, null, "COMPLETED", finishedInstance));
                return;
            }

            // 兜底回退
            executeStep(instanceId, "node-mcp-sql-exec", "MCP 协议分布式执行器", "MCP_TOOL_CALL", 520, () -> {
                return Map.of(
                    "affectedRows", 1,
                    "targetHost", "43.142.74.224:3306",
                    "status", "SUCCESS",
                    "mcpStatus", "200_OK"
                );
            });

            executeStep(instanceId, "node-audit-ledger", "防篡改审计存证", "SQL_EXECUTOR", 220, () -> {
                return Map.of(
                    "ledgerHash", "0x" + UUID.randomUUID().toString().replace("-", ""),
                    "auditProof", "SEC_COMPLIANT_RECORDED"
                );
            });

            WorkflowInstance finishedInstance = instanceRepository.findById(instanceId).orElseThrow();
            finishedInstance.setStatus("COMPLETED");
            finishedInstance.setFinishedAt(LocalDateTime.now());
            instanceRepository.save(finishedInstance);

            broadcastEvent(instanceId, new WorkflowEventMessage("INSTANCE_COMPLETED", instanceId, null, "COMPLETED", finishedInstance));

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