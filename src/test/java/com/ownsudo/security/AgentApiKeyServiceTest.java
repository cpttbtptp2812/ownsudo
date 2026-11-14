package com.ownsudo.security;

import com.ownsudo.entity.AgentApiKeyEntity;
import com.ownsudo.repository.AgentApiKeyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentApiKeyServiceTest {

    private final AgentApiKeyRepository repository = mock(AgentApiKeyRepository.class);
    private final AgentApiKeyService service = new AgentApiKeyService(repository);

    @Test
    void rejectsMissingAndMalformedTokensWithoutDatabaseLookup() {
        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("").isEmpty());
        assertTrue(service.authenticate("not-an-ownagent-key").isEmpty());
        assertTrue(service.authenticate("oa_live_weak").isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void resolvesOnlyActiveNonRevokedKeyToItsAgent() {
        String token = "oa_live_" + "a".repeat(64);
        String hash = AgentApiKeyService.hashToken(token);
        AgentApiKeyEntity entity = new AgentApiKeyEntity();
        entity.setAgentId("agent-123");
        when(repository.findUsableByKeyHash(hash))
                .thenReturn(Optional.of(entity));

        assertEquals(Optional.of("agent-123"), service.authenticate(token));
    }

    @Test
    void unknownKeyDoesNotAuthenticate() {
        String token = "oa_live_" + "b".repeat(64);
        when(repository.findUsableByKeyHash(AgentApiKeyService.hashToken(token)))
                .thenReturn(Optional.empty());

        assertTrue(service.authenticate(token).isEmpty());
    }
}
