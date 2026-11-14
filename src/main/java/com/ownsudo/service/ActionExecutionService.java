package com.ownsudo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ActionEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ActionExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);

    private final ActionExecutionStateService state;
    private final ActionToolHandlerRegistry handlers;
    private final ObjectMapper mapper;
    private final long timeoutSeconds;
    private final long heartbeatIntervalMillis;
    private final ExecutorService handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "ownagent-action-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ActionExecutionService(ActionExecutionStateService state, ActionToolHandlerRegistry handlers,
                                  ObjectMapper mapper,
                                  @Value("${ownagent.runtime.handler-timeout-seconds:60}") long timeoutSeconds,
                                  @Value("${ownagent.runtime.heartbeat-interval-ms:10000}") long heartbeatIntervalMillis) {
        this.state = state;
        this.handlers = handlers;
        this.mapper = mapper;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.heartbeatIntervalMillis = Math.max(1000, heartbeatIntervalMillis);
    }

    /**
     * Executes an allowlisted handler outside state-transition transactions.
     * A heartbeat extends the lease while the handler is active. A timeout is UNKNOWN,
     * because interrupting a Java thread cannot prove that an external side effect stopped.
     */
    public ActionEntity execute(String agentId, String actionId) {
        ActionEntity claimed = state.claim(agentId, actionId);
        String leaseToken = claimed.getLeaseToken();
        final Map<String, Object> arguments;
        final ActionToolHandler handler;
        try {
            arguments = mapper.readValue(claimed.getArgumentsJson(), new TypeReference<Map<String, Object>>() {});
            handler = handlers.require(claimed.getToolName());
        } catch (ActionToolHandlerRegistry.ToolHandlerNotFoundException e) {
            return state.fail(agentId, actionId, leaseToken, "TOOL_HANDLER_NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            return state.fail(agentId, actionId, leaseToken, "INVALID_TOOL_ARGUMENTS", e.getMessage());
        } catch (Exception e) {
            return state.fail(agentId, actionId, leaseToken, "INVALID_TOOL_ARGUMENTS",
                    e.getMessage() == null ? "Could not decode tool arguments" : e.getMessage());
        }

        AtomicReference<RuntimeException> heartbeatFailure = new AtomicReference<>();
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (heartbeatFailure.get() != null) return;
            try {
                state.renewLease(agentId, actionId, leaseToken);
            } catch (RuntimeException e) {
                heartbeatFailure.compareAndSet(null, e);
                log.warn("Action heartbeat could not renew lease for action {}: {}", actionId, e.getMessage());
            }
        }, heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);

        Future<Map<String, Object>> execution = handlerExecutor.submit(() -> handler.execute(arguments));
        try {
            Map<String, Object> output = execution.get(timeoutSeconds, TimeUnit.SECONDS);
            RuntimeException lostLease = heartbeatFailure.get();
            if (lostLease != null) {
                // The state service performs the authoritative lease-token and expiry check.
                log.warn("Action {} completed after a heartbeat failure; finalization will verify lease ownership", actionId);
            }
            return state.succeed(agentId, actionId, leaseToken, output);
        } catch (TimeoutException e) {
            execution.cancel(true);
            heartbeat.cancel(false);
            return state.markUnknown(agentId, actionId, leaseToken, "EXECUTION_TIMEOUT",
                    "Tool execution exceeded " + timeoutSeconds + " seconds. The handler was interrupted, but an external side effect may still have completed; reconcile before retrying.");
        } catch (InterruptedException e) {
            execution.cancel(true);
            heartbeat.cancel(false);
            Thread.currentThread().interrupt();
            return state.markUnknown(agentId, actionId, leaseToken, "EXECUTION_INTERRUPTED",
                    "The execution request was interrupted. The external side-effect outcome is unknown; reconcile before retrying.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IllegalArgumentException invalid) {
                return state.fail(agentId, actionId, leaseToken, "INVALID_TOOL_ARGUMENTS", invalid.getMessage());
            }
            return state.fail(agentId, actionId, leaseToken, "TOOL_EXECUTION_FAILED",
                    cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } catch (ActionExecutionStateService.ActionStateConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            return state.fail(agentId, actionId, leaseToken, "TOOL_EXECUTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    public void shutdownExecutors() {
        heartbeatExecutor.shutdownNow();
        handlerExecutor.close();
    }
}
