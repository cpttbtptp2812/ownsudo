package com.ownsudo.security;

import com.ownsudo.entity.AgentApiKeyEntity;
import com.ownsudo.repository.AgentApiKeyRepository;
import com.ownsudo.repository.AgentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Optional one-agent bootstrap for initial SDK integration.
 * Only the SHA-256 hash is persisted; the raw key remains an operator secret.
 */
@Component
public class AgentApiKeyBootstrap implements CommandLineRunner {

    private final AgentApiKeyRepository keyRepository;
    private final AgentRepository agentRepository;
    private final String agentId;
    private final String rawApiKey;

    public AgentApiKeyBootstrap(
            AgentApiKeyRepository keyRepository,
            AgentRepository agentRepository,
            @Value("${app.security.bootstrap-agent-id:}") String agentId,
            @Value("${app.security.bootstrap-agent-api-key:}") String rawApiKey) {
        this.keyRepository = keyRepository;
        this.agentRepository = agentRepository;
        this.agentId = agentId == null ? "" : agentId.trim();
        this.rawApiKey = rawApiKey == null ? "" : rawApiKey.trim();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (agentId.isBlank() && rawApiKey.isBlank()) return;
        if (agentId.isBlank() || rawApiKey.isBlank()) {
            throw new IllegalStateException("Both OWNAGENT_BOOTSTRAP_AGENT_ID and OWNAGENT_BOOTSTRAP_AGENT_API_KEY must be configured");
        }
        if (!agentRepository.existsById(agentId)) {
            throw new IllegalStateException("Configured bootstrap Agent does not exist: " + agentId);
        }

        String hash = AgentApiKeyService.hashToken(rawApiKey);
        AgentApiKeyEntity key = keyRepository.findByAgentIdAndKeyName(agentId, "bootstrap")
                .orElseGet(() -> {
                    AgentApiKeyEntity created = new AgentApiKeyEntity();
                    created.setId("aak-" + UUID.randomUUID());
                    created.setAgentId(agentId);
                    created.setKeyName("bootstrap");
                    return created;
                });
        key.setKeyHash(hash);
        key.setActive(true);
        key.setRevokedAt(null);
        keyRepository.save(key);
    }
}
