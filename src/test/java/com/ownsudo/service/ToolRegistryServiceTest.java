package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.RegisterToolRequest;
import com.ownsudo.entity.AgentToolEntity;
import com.ownsudo.repository.AgentToolRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolRegistryServiceTest {
    private final AgentToolRepository repository=mock(AgentToolRepository.class);
    private final ToolRegistryService service=new ToolRegistryService(repository,new ObjectMapper());

    @Test void registersToolUnderAuthenticatedAgent() {
        when(repository.findByAgentIdAndToolName("agent-a","file.write")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        AgentToolEntity saved=service.register("agent-a",request());
        assertEquals("agent-a",saved.getAgentId()); assertEquals("file.write",saved.getToolName());
        assertEquals("HIGH",saved.getRiskLevel()); assertTrue(saved.isEnabled());
    }
    @Test void rejectsUnknownRiskLevel() {
        RegisterToolRequest request=request(); request.setRiskLevel("UNSAFE_BUT_ALLOWED");
        assertThrows(IllegalArgumentException.class,()->service.register("agent-a",request));
        verifyNoInteractions(repository);
    }
    @Test void rejectsRequiredFieldMissingFromProperties() {
        RegisterToolRequest request=request();
        request.setInputSchema(Map.of("type","object","properties",Map.of(),"required",List.of("path")));
        assertThrows(IllegalArgumentException.class,()->service.register("agent-a",request));
    }
    @Test void validatesRequiredAndTypedArguments() {
        when(repository.findByAgentIdAndToolName("agent-a","file.write")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        AgentToolEntity tool=service.register("agent-a",request());
        assertThrows(IllegalArgumentException.class,()->service.validateArguments(tool,Map.of("content","x")));
        assertThrows(IllegalArgumentException.class,()->service.validateArguments(tool,Map.of("path",42,"content","x")));
        assertThrows(IllegalArgumentException.class,()->service.validateArguments(tool,Map.of("path","/tmp/a","content","x","extra",true)));
        assertDoesNotThrow(()->service.validateArguments(tool,Map.of("path","/tmp/a","content","x")));
    }
    private RegisterToolRequest request() {
        RegisterToolRequest request=new RegisterToolRequest();
        request.setToolName("file.write"); request.setDescription("Write a file"); request.setRiskLevel("HIGH");
        request.setInputSchema(Map.of("type","object","properties",Map.of(
                "path",Map.of("type","string"),"content",Map.of("type","string")),
                "required",List.of("path","content"),"additionalProperties",false));
        return request;
    }
}
