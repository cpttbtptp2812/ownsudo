package com.ownsudo.repository;

import com.ownsudo.entity.ActionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActionEntity a where a.agentId = :agentId and a.status = 'READY' "
            + "and a.toolName in :toolNames and (a.decision = 'ALLOW' "
            + "or (a.decision = 'REQUIRE_APPROVAL' and a.approvalStatus = 'APPROVED')) "
            + "order by a.createdAt asc")
    List<ActionEntity> findEligibleForWorker(@Param("agentId") String agentId,
                                             @Param("toolNames") List<String> toolNames,
                                             Pageable pageable);

    List<ActionEntity> findAllByAgentIdAndStatusOrderByCreatedAtDesc(String agentId, String status);
}
