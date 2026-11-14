package com.ownsudo.repository;

import com.ownsudo.entity.AgentApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentApiKeyRepository extends JpaRepository<AgentApiKeyEntity, String> {

    @Query("""
            SELECT apiKey
            FROM AgentApiKeyEntity apiKey, AgentEntity agent
            WHERE apiKey.agentId = agent.id
              AND apiKey.keyHash = :keyHash
              AND apiKey.active = true
              AND apiKey.revokedAt IS NULL
              AND COALESCE(LOWER(agent.status), 'published')
                    NOT IN ('disabled', 'deleted', 'inactive', 'suspended')
            """)
    Optional<AgentApiKeyEntity> findUsableByKeyHash(@Param("keyHash") String keyHash);

    Optional<AgentApiKeyEntity> findByAgentIdAndKeyName(String agentId, String keyName);
}
