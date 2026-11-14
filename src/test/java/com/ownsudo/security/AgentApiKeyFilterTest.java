package com.ownsudo.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentApiKeyFilterTest {

    @Test
    void rejectsV1RequestWithoutValidBearerToken() throws Exception {
        AgentApiKeyService service = mock(AgentApiKeyService.class);
        AgentApiKeyFilter filter = new AgentApiKeyFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/identity");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(service.authenticate(null)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void attachesAuthenticatedAgentIdentityBeforeContinuing() throws Exception {
        AgentApiKeyService service = mock(AgentApiKeyService.class);
        AgentApiKeyFilter filter = new AgentApiKeyFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/identity");
        request.addHeader("Authorization", "Bearer oa_live_" + "a".repeat(64));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(service.authenticate("oa_live_" + "a".repeat(64))).thenReturn(Optional.of("agent-123"));

        filter.doFilter(request, response, chain);

        assertEquals("agent-123", request.getAttribute(AgentApiKeyFilter.AGENT_ID_ATTRIBUTE));
        verify(chain).doFilter(request, response);
    }

    @Test
    void doesNotFilterLegacyApiRoutes() throws Exception {
        AgentApiKeyService service = mock(AgentApiKeyService.class);
        AgentApiKeyFilter filter = new AgentApiKeyFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(service);
        verify(chain).doFilter(request, response);
    }
}
