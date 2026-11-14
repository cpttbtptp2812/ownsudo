package com.ownsudo.repository;

import com.ownsudo.entity.AgentToolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AgentToolRepository extends JpaRepository<AgentToolEntity, String> {
    Optional<AgentToolEntity> findByAgentIdAndToolName(String agentId, String toolName);
    List<AgentToolEntity> findAllByAgentIdOrderByToolNameAsc(String agentId);
}