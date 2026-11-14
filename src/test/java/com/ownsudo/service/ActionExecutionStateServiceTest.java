package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.repository.ActionEventRepository;
import com.ownsudo.repository.ActionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionExecutionStateServiceTest {
    private final ActionRepository actions = mock(ActionRepository.class);
    private final ActionEventRepository events = mock(ActionEventRepository.class);
    private final ActionExecutionStateService service =
            new ActionExecutionStateService(actions, events, new ObjectMapper(), 300);

    @Test
    void claimsOnlyAllowedReadyActionAndPersistsLeaseAndStartedEvent() {
        ActionEntity action = action("ALLOW", "READY");
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(action));
        when(actions.saveAndFlush(action)).thenReturn(action);

        ActionEntity claimed = service.claim("agent-a", "act-1");

        assertEquals("RUNNING", claimed.getStatus());
        assertEquals(1, claimed.getAttemptCount());
        assertNotNull(claimed.getStartedAt());
        assertNotNull(claimed.getLeaseToken());
        assertTrue(claimed.getLeaseUntil().isAfter(LocalDateTime.now()));
        verify(events).save(argThat(event -> "ACTION_STARTED".equals(event.getEventType())
                && "act-1".equals(event.getActionId())));
    }

    @Test
    void heartbeatRenewsOnlyTheCurrentUnexpiredLease() {
        ActionEntity running = action("ALLOW", "RUNNING");
        running.setLeaseToken("lease-1");
        running.setLeaseUntil(LocalDateTime.now().plusSeconds(60));
        LocalDateTime previousExpiry = running.getLeaseUntil();
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(running));
        when(actions.saveAndFlush(running)).thenReturn(running);

        ActionEntity renewed = service.renewLease("agent-a", "act-1", "lease-1");

        assertTrue(renewed.getLeaseUntil().isAfter(previousExpiry));
        verify(events, never()).save(any());
    }

    @Test
    void heartbeatCannotResurrectAnExpiredLease() {
        ActionEntity expired = action("ALLOW", "RUNNING");
        expired.setLeaseToken("lease-1");
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(expired));

        assertThrows(ActionExecutionStateService.ActionStateConflictException.class,
                () -> service.renewLease("agent-a", "act-1", "lease-1"));
        verify(actions, never()).saveAndFlush(any());
    }

    @Test
    void timeoutMarksUnknownAndInvalidatesTheLeaseToken() {
        ActionEntity running = action("ALLOW", "RUNNING");
        running.setLeaseToken("lease-1");
        running.setLeaseUntil(LocalDateTime.now().plusSeconds(60));
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(running));
        when(actions.saveAndFlush(running)).thenReturn(running);

        ActionEntity unknown = service.markUnknown("agent-a", "act-1", "lease-1",
                "EXECUTION_TIMEOUT", "Timed out");

        assertEquals("UNKNOWN", unknown.getStatus());
        assertEquals("EXECUTION_TIMEOUT", unknown.getErrorCode());
        assertNull(unknown.getLeaseToken());
        assertNull(unknown.getLeaseUntil());
        verify(events).save(argThat(event -> "ACTION_EXECUTION_UNKNOWN".equals(event.getEventType())));
    }

    @Test
    void approvalRequiredActionCannotBeClaimed() {
        ActionEntity action = action("REQUIRE_APPROVAL", "PENDING_APPROVAL");
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(action));

        assertThrows(ActionExecutionStateService.ActionStateConflictException.class,
                () -> service.claim("agent-a", "act-1"));
        verify(actions, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void deniedActionCannotBeClaimed() {
        ActionEntity action = action("DENY", "DENIED");
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(action));

        assertThrows(ActionExecutionStateService.ActionStateConflictException.class,
                () -> service.claim("agent-a", "act-1"));
        verify(actions, never()).saveAndFlush(any());
    }

    @Test
    void expiredRunningActionBecomesUnknownAndIsNotRequeued() {
        ActionEntity expired = action("ALLOW", "RUNNING");
        expired.setLeaseToken("old-lease");
        expired.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
        when(actions.findAllByStatusAndLeaseUntilBefore(eq("RUNNING"), any(LocalDateTime.class), any()))
                .thenReturn(List.of(expired));
        when(actions.saveAndFlush(expired)).thenReturn(expired);

        int recovered = service.recoverExpiredLeases();

        assertEquals(1, recovered);
        assertEquals("UNKNOWN", expired.getStatus());
        assertEquals("EXECUTION_LEASE_EXPIRED", expired.getErrorCode());
        assertNull(expired.getLeaseToken());
        assertNull(expired.getLeaseUntil());
        verify(events).save(argThat(event -> "ACTION_LEASE_EXPIRED".equals(event.getEventType())));
    }

    private ActionEntity action(String decision, String status) {
        ActionEntity action = new ActionEntity();
        action.setId("act-1");
        action.setAgentId("agent-a");
        action.setDecision(decision);
        action.setStatus(status);
        action.setAttemptCount(0);
        return action;
    }
}
