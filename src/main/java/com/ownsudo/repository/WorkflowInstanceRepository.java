package com.ownsudo.repository;

import com.ownsudo.entity.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, String> {
    List<WorkflowInstance> findByWorkflowIdOrderByStartedAtDesc(String workflowId);
    List<WorkflowInstance> findTop20ByOrderByStartedAtDesc();
}
