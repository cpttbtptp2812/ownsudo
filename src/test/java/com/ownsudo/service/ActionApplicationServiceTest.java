package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.CreateActionRequest;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.entity.AgentToolEntity;
import com.ownsudo.repository.ActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionApplicationServiceTest {
    private final ActionRepository repository = mock(ActionRepository.class);
    private final ToolRegistryService toolRegistry = mock(ToolRegistryService.class);
    private final ActionApplicationService service =
            new ActionApplicationService(repository, new ObjectMapper(), toolRegistry, new ActionPolicyEngine());

    private void allowTool(String agentId) {
        AgentToolEntity tool = new AgentToolEntity();
        tool.setAgentId(agentId); tool.setToolName("file.write"); tool.setRiskLevel("LOW"); tool.setEnabled(true);
        when(toolRegistry.find(agentId, "file.write")).thenReturn(Optional.of(tool));
    }

    @Test void createsAllowedActionOwnedByAuthenticatedAgent() {
        allowTool("agent-authenticated");
        when(repository.findByAgentIdAndIdempotencyKey("agent-authenticated", "req-001")).thenReturn(Optional.empty());
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.create("agent-authenticated", request("req-001", Map.of("path", "/tmp/report.txt")));
        assertTrue(result.created());
        assertEquals("agent-authenticated", result.action().getAgentId());
        assertEquals("READY", result.action().getStatus());
        assertEquals("ALLOW", result.action().getDecision());
        assertEquals("LOW", result.action().getRiskLevel());
        verify(repository).save(any(ActionEntity.class));
    }

    @Test void sameAgentAndSameIdempotencyRequestReturnsExistingAction() {
        allowTool("agent-a");
        ActionEntity stored = new ActionEntity();
        stored.setId("act-existing"); stored.setAgentId("agent-a");
        stored.setRequestHash(hashFor("file.write", Map.of("a", 1, "b", 2)));
        when(repository.findByAgentIdAndIdempotencyKey("agent-a", "same-key")).thenReturn(Optional.empty(), Optional.of(stored));
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String,Object> first=new LinkedHashMap<>(); first.put("b",2); first.put("a",1);
        Map<String,Object> second=new LinkedHashMap<>(); second.put("a",1); second.put("b",2);
        var created=service.create("agent-a",request("same-key",first));
        var replayed=service.create("agent-a",request("same-key",second));
        assertTrue(created.created()); assertFalse(replayed.created()); assertEquals("act-existing",replayed.action().getId());
        verify(repository,times(1)).save(any(ActionEntity.class));
    }

    @Test void idempotencyKeysAreIsolatedBetweenAgents() {
        allowTool("agent-a"); allowTool("agent-b");
        when(repository.findByAgentIdAndIdempotencyKey("agent-a","shared-key")).thenReturn(Optional.empty());
        when(repository.findByAgentIdAndIdempotencyKey("agent-b","shared-key")).thenReturn(Optional.empty());
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var first=service.create("agent-a",request("shared-key",Map.of("path","/a.txt")));
        var second=service.create("agent-b",request("shared-key",Map.of("path","/a.txt")));
        assertEquals("agent-a",first.action().getAgentId()); assertEquals("agent-b",second.action().getAgentId());
        assertNotEquals(first.action().getId(),second.action().getId());
    }

    @Test void differentOperationWithSameIdempotencyKeyIsConflict() {
        allowTool("agent-a");
        ActionEntity stored=new ActionEntity(); stored.setId("act-existing"); stored.setRequestHash(hashFor("file.write",Map.of("path","/safe.txt")));
        when(repository.findByAgentIdAndIdempotencyKey("agent-a","same-key")).thenReturn(Optional.of(stored));
        assertThrows(ActionApplicationService.IdempotencyConflictException.class,
                ()->service.create("agent-a",request("same-key",Map.of("path","/other.txt"))));
        verify(repository,never()).save(any(ActionEntity.class));
    }

    @Test void unknownToolIsDeniedWithoutExecution() {
        when(repository.findByAgentIdAndIdempotencyKey("agent-a","unknown-key")).thenReturn(Optional.empty());
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result=service.create("agent-a",request("unknown-key",Map.of("path","/tmp/x")));
        assertEquals("DENY",result.action().getDecision()); assertEquals("DENIED",result.action().getStatus());
    }

    @Test void actionLookupIsScopedToAuthenticatedAgent() {
        when(repository.findByIdAndAgentId("act-foreign","agent-b")).thenReturn(Optional.empty());
        assertTrue(service.find("agent-b","act-foreign").isEmpty());
        verify(repository).findByIdAndAgentId("act-foreign","agent-b");
    }

    @Test void listingIsScopedAndLimitIsCapped() {
        when(repository.findAllByAgentIdOrderByCreatedAtDesc(eq("agent-b"),any(Pageable.class))).thenReturn(List.of());
        service.list("agent-b",500);
        verify(repository).findAllByAgentIdOrderByCreatedAtDesc(eq("agent-b"),argThat(p->p.getPageSize()==100));
    }

    @Test void rejectsMissingIdempotencyKeyAndMissingAuthenticatedIdentity() {
        assertThrows(IllegalArgumentException.class,()->service.create("agent-a",request(" ",Map.of("x",1))));
        assertThrows(IllegalArgumentException.class,()->service.create("",request("key",Map.of("x",1))));
        verifyNoInteractions(repository);
    }

    private CreateActionRequest request(String key,Map<String,Object> args) {
        CreateActionRequest r=new CreateActionRequest(); r.setToolName("file.write"); r.setArguments(args); r.setIdempotencyKey(key); return r;
    }
    private String hashFor(String toolName,Map<String,Object> args) {
        try {
            ObjectMapper mapper=new ObjectMapper(); var operation=mapper.createObjectNode(); operation.put("toolName",toolName);
            var arguments=mapper.createObjectNode();
            args.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->arguments.set(e.getKey(),mapper.valueToTree(e.getValue())));
            operation.set("arguments",arguments);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(operation)));
        } catch(Exception e){throw new IllegalStateException(e);}
    }
}
