package com.ownsudo.repository;

import com.ownsudo.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, String> {
    List<WorkflowDefinition> findAllByOrderBySortOrderAscCreatedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkflowDefinition w WHERE w.id = :id")
    int deleteByIdDirectly(@Param("id") String id);
}
