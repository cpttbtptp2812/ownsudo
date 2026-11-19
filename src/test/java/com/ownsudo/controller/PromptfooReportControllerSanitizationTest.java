package com.ownsudo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class PromptfooReportControllerSanitizationTest {
    @Test
    void removesReasoningFieldsAndAssistantAnalysisMessagesRecursively() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PromptfooReportController controller = new PromptfooReportController(null, mapper);
        JsonNode input = mapper.readTree("""
                {
                  "results": [{
                    "success": true,
                    "reasoning_content": "private model reasoning",
                    "messages": [
                      {"role": "assistant", "channel": "analysis", "content": "hidden thought"},
                      {"role": "assistant", "channel": "final", "content": "safe answer"},
                      {"role": "user", "channel": "analysis", "content": "keep user input"}
                    ],
                    "nested": {"scratchpad": "private", "answer": "visible"}
                  }]
                }
                """);
        Method method = PromptfooReportController.class.getDeclaredMethod("sanitize", JsonNode.class);
        method.setAccessible(true);
        JsonNode result = (JsonNode) method.invoke(controller, input);

        JsonNode row = result.path("results").get(0);
        assertFalse(row.has("reasoning_content"));
        assertFalse(row.path("nested").has("scratchpad"));
        assertEquals(2, row.path("messages").size());
        assertEquals("safe answer", row.path("messages").get(0).path("content").asText());
        assertEquals("keep user input", row.path("messages").get(1).path("content").asText());
    }
}
