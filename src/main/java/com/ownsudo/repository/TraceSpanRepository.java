package com.ownsudo.repository;

import com.ownsudo.entity.TraceSpanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TraceSpanRepository extends JpaRepository<TraceSpanEntity, String> {
    List<TraceSpanEntity> findTop500ByAgentIdOrderByCreatedAtDesc(String agentId);
    Optional<TraceSpanEntity> findByAgentIdAndTraceIdAndSpanId(String agentId, String traceId, String spanId);
    List<TraceSpanEntity> findAllByAgentIdAndTraceIdOrderByStartedAtAsc(String agentId, String traceId);
}
