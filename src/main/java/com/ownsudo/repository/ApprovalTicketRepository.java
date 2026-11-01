package com.ownsudo.repository;

import com.ownsudo.entity.ApprovalTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalTicketRepository extends JpaRepository<ApprovalTicket, String> {
    List<ApprovalTicket> findAllByOrderByCreatedAtDesc();
    List<ApprovalTicket> findByStatusOrderByCreatedAtDesc(String status);
    Optional<ApprovalTicket> findByInstanceIdAndNodeId(String instanceId, String nodeId);
    List<ApprovalTicket> findByInstanceIdOrderByCreatedAtDesc(String instanceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ApprovalTicket t WHERE t.instanceId = :instanceId")
    void deleteByInstanceId(@Param("instanceId") String instanceId);
}
