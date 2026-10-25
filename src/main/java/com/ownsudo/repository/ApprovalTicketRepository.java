package com.ownsudo.repository;

import com.ownsudo.entity.ApprovalTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalTicketRepository extends JpaRepository<ApprovalTicket, String> {
    List<ApprovalTicket> findByStatusOrderByCreatedAtDesc(String status);
    Optional<ApprovalTicket> findByInstanceIdAndNodeId(String instanceId, String nodeId);
}
