package com.ownsudo.repository;

import com.ownsudo.entity.AgentMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentMemoryRepository extends JpaRepository<AgentMemory, String> {
    List<AgentMemory> findBySessionIdOrderByLastAccessedAtDesc(String sessionId);
    Optional<AgentMemory> findBySessionIdAndReferenceEntity(String sessionId, String referenceEntity);
}
