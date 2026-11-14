package com.ownsudo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class AgentApiKeyFilter extends OncePerRequestFilter {

    public static final String AGENT_ID_ATTRIBUTE = "ownagent.agentId";
    private final AgentApiKeyService apiKeyService;

    public AgentApiKeyFilter(AgentApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorization = request.getHeader("Authorization");
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7).trim();
        }

        Optional<String> agentId = apiKeyService.authenticate(token);
        if (agentId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"A valid OwnAgent Agent API key is required\"}}");
            return;
        }

        request.setAttribute(AGENT_ID_ATTRIBUTE, agentId.get());
        filterChain.doFilter(request, response);
    }
}
