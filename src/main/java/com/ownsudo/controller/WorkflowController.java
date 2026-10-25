package com.ownsudo.controller;

import com.ownsudo.dto.CommitApprovalRequest;
import com.ownsudo.dto.RunWorkflowRequest;
import com.ownsudo.entity.ApprovalTicket;
import com.ownsudo.entity.NodeExecution;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.entity.WorkflowInstance;
import com.ownsudo.repository.ApprovalTicketRepository;
import com.ownsudo.repository.NodeExecutionRepository;
import com.ownsudo.repository.WorkflowInstanceRepository;
import com.ownsudo.service.WorkflowDefinitionService;
import com.ownsudo.service.WorkflowEngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/instances/{id}")
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
}
