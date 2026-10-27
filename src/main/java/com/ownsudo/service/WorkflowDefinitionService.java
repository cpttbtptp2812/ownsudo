package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.repository.WorkflowDefinitionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkflowDefinitionService {

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // 严禁 @PostConstruct 硬编码预置伪造工作流，所有工作流均存入数据库由用户真实创建与管理
    public List<WorkflowDefinition> getAllDefinitions() {
        return definitionRepository.findAllByOrderBySortOrderAscCreatedAtDesc();
    }

    public Optional<WorkflowDefinition> getById(String id) {
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

    public void delete(String id) {
        definitionRepository.deleteById(id);
    }
}
