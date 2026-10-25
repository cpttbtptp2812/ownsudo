package com.ownsudo.repository;

import com.ownsudo.entity.NodeExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, String> {
    List<NodeExecution> findByInstanceIdOrderByStartedAtAsc(String instanceId);
    Optional<NodeExecution> findByInstanceIdAndNodeId(String instanceId, String nodeId);
}
