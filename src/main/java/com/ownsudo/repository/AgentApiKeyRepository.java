package com.ownsudo.repository;

import com.ownsudo.entity.AgentApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentApiKeyRepository extends JpaRepository<AgentApiKeyEntity, String> {
    Optional<AgentApiKeyEntity> findByKeyHashAndActiveTrueAndRevokedAtIsNull(String keyHash);
    Optional<AgentApiKeyEntity> findByAgentIdAndKeyName(String agentId, String keyName);
}
