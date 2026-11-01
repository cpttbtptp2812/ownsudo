package com.ownsudo.repository;

import com.ownsudo.entity.GitRepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface GitRepositoryEntityRepository extends JpaRepository<GitRepositoryEntity, String> {
    List<GitRepositoryEntity> findAllByOrderByCreatedAtDesc();

    List<GitRepositoryEntity> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);

    @Query("SELECT r FROM GitRepositoryEntity r WHERE (:workflowId IS NULL OR :workflowId = '' OR r.workflowId = :workflowId OR r.workflowId IS NULL OR r.workflowId = '') ORDER BY r.createdAt DESC")
    List<GitRepositoryEntity> findByWorkflowIdOrGlobalOrderByCreatedAtDesc(@Param("workflowId") String workflowId);

    @Modifying
    @Transactional
    @Query("DELETE FROM GitRepositoryEntity r WHERE r.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") String workflowId);
}
