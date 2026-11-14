package com.ownsudo.repository;

import com.ownsudo.entity.ActionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ActionEventRepository extends JpaRepository<ActionEventEntity, String> {
    List<ActionEventEntity> findAllByAgentIdAndActionIdOrderByCreatedAtAsc(String agentId, String actionId);
}