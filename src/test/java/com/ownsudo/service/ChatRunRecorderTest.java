package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatRunRecorderTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatRunRecorder recorder = new ChatRunRecorder(jdbc, new ObjectMapper());

    @Test
    void persistsRunEventsSpansAndProviderUsageWithoutInternalReasoning() {
        doReturn(0).when(jdbc).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));

        Map<String, Object> run = recorder.start("session-1", "agent-1", "Demo Agent",
                "qwen-plus", "dashscope", "请检查最近一次执行");
        String runId = String.valueOf(run.get("runId"));

        recorder.recordStep("model-call-1", 41, "llm", "RUNNING", "模型请求", "正在调用模型",
                Map.of("prompt_messages", "[{\"role\":\"user\",\"content\":\"hello\"}]"));
        recorder.recordStep("model-call-1", 41, "llm", "DONE", "模型响应", "模型返回",
                Map.of("usage", Map.of("prompt_tokens", 17, "completion_tokens", 9),
                        "model_response", Map.of("content", "真实模型结果", "reasoning_content", "must not persist")));
        // Repeated completion updates for the same model span must not double-count provider usage.
        recorder.recordStep("model-call-1", 41, "llm", "DONE", "模型响应更新", "重复事件不重复计费",
                Map.of("usage", Map.of("prompt_tokens", 17, "completion_tokens", 9)));
        recorder.recordToolStart("call-1", "lookup_order", Map.of("orderId", "O-1"));
        recorder.recordToolResult("call-1", "lookup_order", true, 23,
                Map.of("orderStatus", "shipped"), null);
        recorder.complete(17, 9, true, "COMPLETED");
        recorder.clear();

        verify(jdbc).update(contains("INSERT INTO chat_agent_run\n"), any(Object[].class));
        ArgumentCaptor<Object[]> eventArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(4)).update(contains("INSERT INTO chat_agent_run_event"), eventArgs.capture());
        assertFalse(eventArgs.getAllValues().stream()
                .anyMatch(args -> String.valueOf(args[6]).contains("请检查最近一次执行")));
        Object[] userMessageEvent = eventArgs.getAllValues().stream()
                .filter(args -> "USER_MESSAGE".equals(args[3]))
                .findFirst().orElseThrow();
        assertEquals("{\"captured\":false}", userMessageEvent[6]);
        verify(jdbc, atLeast(2)).update(contains("INSERT INTO chat_agent_run_span"), any(Object[].class));
        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("SET status = ?"), updateArgs.capture());
        assertEquals(runId, updateArgs.getValue()[8]);
        assertEquals("PROVIDER", updateArgs.getValue()[6]);
        assertEquals(17L, updateArgs.getValue()[3]);
        assertEquals(9L, updateArgs.getValue()[4]);
        assertEquals(26L, updateArgs.getValue()[5]);
    }

    
    @Test
    void marksAbortedRunAsUnknownWithoutInventingTokenUsage() {
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));

        recorder.start("session-aborted", "agent-1", "Disconnect Demo",
                "qwen-plus", "dashscope", "disconnect test");
        recorder.complete(0, 0, false, "ABORTED");
        recorder.clear();

        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("SET status = ?"), updateArgs.capture());
        assertEquals("UNKNOWN", updateArgs.getValue()[0]);
        assertEquals(null, updateArgs.getValue()[3]);
        assertEquals(null, updateArgs.getValue()[4]);
        assertEquals(null, updateArgs.getValue()[5]);
        assertEquals("MISSING", updateArgs.getValue()[6]);
    }

}
