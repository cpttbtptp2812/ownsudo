package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import com.ownsudo.entity.ActionEventEntity;
import com.ownsudo.repository.ActionEventRepository;
import com.ownsudo.repository.ActionRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionExecutionStateServiceTest {
    private final ActionRepository actions = mock(ActionRepository.class);
    private final ActionEventRepository events = mock(ActionEventRepository.class);
    private final ActionExecutionStateService service =
            new ActionExecutionStateService(actions, events, new ObjectMapper());

    @Test
    void claimsOnlyAllowedReadyActionAndPersistsStartedEvent() {
        ActionEntity action = action("ALLOW", "READY");
        when(actions.findByIdAndAgentId("act-1", "agent-a")).thenReturn(Optional.of(action));
        when(actions.saveAndFlush(action)).thenReturn(action);

        ActionEntity claimed = service.claim("agent-a", "act-1");

        assertEquals("RUNNING", claimed.getStatus());
        assertEquals(1, claimed.getAttemptCount());
        assertNotNull(claimed.getStartedAt());
        verify(events).save(argThat(event -> "ACTION_STARTED".equals(event.getEventType())
                && "act-1".equals(event.getActionId())));
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
