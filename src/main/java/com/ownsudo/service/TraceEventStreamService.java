package com.ownsudo.service;

import com.ownsudo.dto.TraceSpanResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Streams persisted trace-span updates to authenticated Agent clients.
 *
 * This is a live notification channel, not the source of truth: clients should
 * load existing spans from GET /{traceId}/spans before subscribing.
 */
@Service
public class TraceEventStreamService {
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int MAX_SUBSCRIBERS_PER_TRACE = 10;

    private final Map<StreamKey, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String agentId, String traceId) {
        StreamKey key = new StreamKey(agentId, traceId);
        CopyOnWriteArrayList<SseEmitter> emitters =
                subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());

        if (emitters.size() >= MAX_SUBSCRIBERS_PER_TRACE) {
            throw new IllegalStateException("Too many live subscribers for this trace");
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        Runnable cleanup = () -> remove(key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "traceId", traceId,
                    "message", "Live trace stream connected"
            )));
        } catch (IOException error) {
            cleanup.run();
            emitter.completeWithError(error);
        }
        return emitter;
    }

    public void publish(String agentId, TraceSpanResponse span) {
        StreamKey key = new StreamKey(agentId, span.traceId());
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("trace_span")
                        .id(span.spanId())
                        .data(span));
            } catch (IOException | IllegalStateException error) {
                remove(key, emitter);
                emitter.completeWithError(error);
            }
        }
    }

    private void remove(StreamKey key, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) subscribers.remove(key, emitters);
    }

    private record StreamKey(String agentId, String traceId) {}
}
