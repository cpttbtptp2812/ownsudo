package com.ownsudo.repository;

import com.ownsudo.entity.ActionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ActionRepository extends JpaRepository<ActionEntity, String> {
    Optional<ActionEntity> findByAgentIdAndIdempotencyKey(String agentId, String idempotencyKey);
    Optional<ActionEntity> findByIdAndAgentId(String id, String agentId);
    List<ActionEntity> findAllByAgentIdOrderByCreatedAtDesc(String agentId, Pageable pageable);
    List<ActionEntity> findAllByStatusAndLeaseUntilBefore(String status, LocalDateTime cutoff, Pageable pageable);
}
