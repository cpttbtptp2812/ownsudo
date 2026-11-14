package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.CreateActionRequest;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.repository.ActionRepository;\nimport com.ownsudo.repository.AgentToolRepository;\nimport com.ownsudo.entity.AgentToolEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionApplicationServiceTest {

    private final ActionRepository repository = mock(ActionRepository.class);
    private final ActionApplicationService service =
            new ActionApplicationService(repository, new ObjectMapper());

    @Test
    void createsPendingActionOwnedByAuthenticatedAgent() {
        when(repository.findByAgentIdAndIdempotencyKey("agent-authenticated", "req-001"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActionApplicationService.CreateResult result =
                service.create("agent-authenticated", request("req-001", Map.of("path", "/tmp/report.txt")));

        assertTrue(result.created());
        assertEquals("agent-authenticated", result.action().getAgentId());
        assertEquals("PENDING_POLICY", result.action().getStatus());
        assertEquals("UNDECIDED", result.action().getDecision());
        assertEquals("UNASSESSED", result.action().getRiskLevel());
        assertEquals("file.write", result.action().getToolName());
        verify(repository).save(any(ActionEntity.class));
    }

    @Test
    void sameAgentAndSameIdempotencyRequestReturnsExistingAction() {
        ActionEntity stored = new ActionEntity();
        stored.setId("act-existing");
        stored.setAgentId("agent-a");
        stored.setRequestHash(hashFor("file.write", Map.of("a", 1, "b", 2)));
        when(repository.findByAgentIdAndIdempotencyKey("agent-a", "same-key"))
                .thenReturn(Optional.empty(), Optional.of(stored));
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);

        ActionApplicationService.CreateResult created = service.create("agent-a", request("same-key", first));
        ActionApplicationService.CreateResult replayed = service.create("agent-a", request("same-key", second));

        assertTrue(created.created());
        assertFalse(replayed.created());
        assertEquals("act-existing", replayed.action().getId());
        verify(repository, times(1)).save(any(ActionEntity.class));
    }

    @Test
    void idempotencyKeysAreIsolatedBetweenAgents() {
        when(repository.findByAgentIdAndIdempotencyKey("agent-a", "shared-key"))
                .thenReturn(Optional.empty());
        when(repository.findByAgentIdAndIdempotencyKey("agent-b", "shared-key"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActionApplicationService.CreateResult first =
                service.create("agent-a", request("shared-key", Map.of("path", "/a.txt")));
        ActionApplicationService.CreateResult second =
                service.create("agent-b", request("shared-key", Map.of("path", "/a.txt")));

        assertTrue(first.created());
        assertTrue(second.created());
        assertEquals("agent-a", first.action().getAgentId());
        assertEquals("agent-b", second.action().getAgentId());
        assertNotEquals(first.action().getId(), second.action().getId());
        verify(repository).findByAgentIdAndIdempotencyKey("agent-a", "shared-key");
        verify(repository).findByAgentIdAndIdempotencyKey("agent-b", "shared-key");
    }

    @Test
    void sameIdempotencyKeyWithDifferentOperationIsConflict() {
        ActionEntity stored = new ActionEntity();
        stored.setId("act-existing");
        stored.setAgentId("agent-a");
        stored.setRequestHash(hashFor("file.write", Map.of("path", "/safe.txt")));
        when(repository.findByAgentIdAndIdempotencyKey("agent-a", "same-key"))
                .thenReturn(Optional.of(stored));

        assertThrows(ActionApplicationService.IdempotencyConflictException.class,
                () -> service.create("agent-a", request("same-key", Map.of("path", "/other.txt"))));
        verify(repository, never()).save(any(ActionEntity.class));
    }

    @Test
    void actionLookupIsAlwaysScopedToAuthenticatedAgent() {
        when(repository.findByIdAndAgentId("act-foreign", "agent-b")).thenReturn(Optional.empty());

        assertTrue(service.find("agent-b", "act-foreign").isEmpty());
        verify(repository).findByIdAndAgentId("act-foreign", "agent-b");
    }

    @Test
    void listingIsScopedAndLimitIsCappedAtOneHundred() {
        when(repository.findAllByAgentIdOrderByCreatedAtDesc(eq("agent-b"), any(Pageable.class)))
                .thenReturn(List.of());

        service.list("agent-b", 500);

        verify(repository).findAllByAgentIdOrderByCreatedAtDesc(eq("agent-b"),
                argThat(pageable -> pageable.getPageSize() == 100));
    }

    @Test
    void rejectsMissingIdempotencyKeyAndMissingAuthenticatedIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("agent-a", request(" ", Map.of("x", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("", request("key", Map.of("x", 1))));
        verifyNoInteractions(repository);
    }

    @Test\n    void unregisteredToolIsDeniedWithoutExecution() {\n        when(repository.findByAgentIdAndIdempotencyKey("agent-a", "unknown-key")).thenReturn(Optional.empty());\n        when(repository.save(any(ActionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));\n        var result=service.create("agent-a", request("unknown-key", Map.of("path","/tmp/x")));\n        assertEquals("DENY", result.action().getDecision());\n        assertEquals("DENIED", result.action().getStatus());\n        assertEquals("UNASSESSED", result.action().getRiskLevel());\n    }\n\n    private CreateActionRequest request(String idempotencyKey, Map<String, Object> arguments) {
        CreateActionRequest request = new CreateActionRequest();
        request.setToolName("file.write");
        request.setArguments(arguments);
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private String hashFor(String toolName, Map<String, Object> args) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            var operation = mapper.createObjectNode();
            operation.put("toolName", toolName);
            var arguments = mapper.createObjectNode();
            args.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> arguments.set(entry.getKey(), mapper.valueToTree(entry.getValue())));
            operation.set("arguments", arguments);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(operation));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
