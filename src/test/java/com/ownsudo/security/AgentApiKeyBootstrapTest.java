package com.ownsudo.security;

import com.ownsudo.entity.AgentApiKeyEntity;
import com.ownsudo.entity.AgentEntity;
import com.ownsudo.repository.AgentApiKeyRepository;
import com.ownsudo.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentApiKeyBootstrapTest {

    private static final String AGENT_ID = "ownagent-local-demo";
    private static final String RAW_KEY = "oa_live_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void createsLocalDemoAgentWhenEnabledAndStoresOnlyKeyHash() throws Exception {
        AgentApiKeyRepository keys = mock(AgentApiKeyRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.existsById(AGENT_ID)).thenReturn(false);
        when(keys.findByAgentIdAndKeyName(AGENT_ID, "bootstrap")).thenReturn(Optional.empty());
        when(keys.save(any(AgentApiKeyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentApiKeyBootstrap bootstrap = new AgentApiKeyBootstrap(keys, agents, AGENT_ID, RAW_KEY, true);
        bootstrap.run();

        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agents).save(agentCaptor.capture());
        assertEquals(AGENT_ID, agentCaptor.getValue().getId());
        assertEquals("OwnAgent Local Demo Agent", agentCaptor.getValue().getName());

        ArgumentCaptor<AgentApiKeyEntity> keyCaptor = ArgumentCaptor.forClass(AgentApiKeyEntity.class);
        verify(keys).save(keyCaptor.capture());
        assertEquals(AgentApiKeyService.hashToken(RAW_KEY), keyCaptor.getValue().getKeyHash());
        assertNotEquals(RAW_KEY, keyCaptor.getValue().getKeyHash());
        assertTrue(keyCaptor.getValue().isActive());
    }

    @Test
    void refusesToCreateMissingAgentOutsideExplicitLocalBootstrapMode() {
        AgentApiKeyRepository keys = mock(AgentApiKeyRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.existsById(AGENT_ID)).thenReturn(false);

        AgentApiKeyBootstrap bootstrap = new AgentApiKeyBootstrap(keys, agents, AGENT_ID, RAW_KEY, false);

        IllegalStateException error = assertThrows(IllegalStateException.class, bootstrap::run);
        assertTrue(error.getMessage().contains("does not exist"));
        verify(agents, never()).save(any(AgentEntity.class));
        verify(keys, never()).save(any(AgentApiKeyEntity.class));
    }

    @Test
    void doesNothingWhenBootstrapCredentialsAreNotConfigured() throws Exception {
        AgentApiKeyRepository keys = mock(AgentApiKeyRepository.class);
        AgentRepository agents = mock(AgentRepository.class);

        new AgentApiKeyBootstrap(keys, agents, "", "", true).run();

        verifyNoInteractions(keys, agents);
    }
}
