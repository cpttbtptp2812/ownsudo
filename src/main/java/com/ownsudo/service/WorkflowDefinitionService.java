package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.entity.WorkflowInstance;
import com.ownsudo.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WorkflowDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionService.class);

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private GitRepositoryEntityRepository gitRepository;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;

    @Autowired
    private ApprovalTicketRepository approvalTicketRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // 严禁 @PostConstruct 硬编码预置伪造工作流，所有工作流均存入数据库由用户真实创建与管理
    public List<WorkflowDefinition> getAllDefinitions() {
        return definitionRepository.findAllByOrderBySortOrderAscCreatedAtDesc();
    }

    public Optional<WorkflowDefinition> getById(String id) {
        return definitionRepository.findById(id);
    }

    public Optional<WorkflowDefinition> getDefinitionById(String id) {
        return definitionRepository.findById(id);
    }

    public WorkflowDefinition save(WorkflowDefinition def) {
        if (def.getId() == null || def.getId().isBlank()) {
            def.setId("wf-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (def.getCreatedAt() == null) {
            def.setCreatedAt(java.time.LocalDateTime.now());
        }
        def.setUpdatedAt(java.time.LocalDateTime.now());
        return definitionRepository.save(def);
    }

    @Transactional
    public void delete(String id) {
        if (id == null || id.isBlank()) return;

        // 1. 级联清理绑定至该流水线的 Git 仓库
        try {
            gitRepository.deleteByWorkflowId(id);
        } catch (Exception e) {
            log.warn("清理关联仓库失败: " + e.getMessage());
        }

        // 2. 级联清理该流水线历史运行实例及其节点明细与审批工单
        try {
            List<WorkflowInstance> instances = instanceRepository.findByWorkflowIdOrderByStartedAtDesc(id);
            for (WorkflowInstance inst : instances) {
                try {
                    nodeExecutionRepository.deleteByInstanceId(inst.getId());
                } catch (Exception ignored) {}
                try {
                    approvalTicketRepository.deleteByInstanceId(inst.getId());
                } catch (Exception ignored) {}
            }
            instanceRepository.deleteByWorkflowId(id);
        } catch (Exception e) {
            log.warn("清理流水线运行实例失败: " + e.getMessage());
        }

        // 3. 高效执行主定义物理删除
        try {
            definitionRepository.deleteByIdDirectly(id);
        } catch (Exception e) {
            log.warn("deleteByIdDirectly 降级: " + e.getMessage());
            definitionRepository.deleteById(id);
        }
    }
}
