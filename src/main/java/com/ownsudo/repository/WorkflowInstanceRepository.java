package com.ownsudo.repository;

import com.ownsudo.entity.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, String> {
    List<WorkflowInstance> findByWorkflowIdOrderByStartedAtDesc(String workflowId);
    List<WorkflowInstance> findTop20ByOrderByStartedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkflowInstance i WHERE i.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") String workflowId);
}
