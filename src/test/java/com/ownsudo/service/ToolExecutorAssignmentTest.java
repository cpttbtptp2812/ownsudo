package com.ownsudo.service;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorAssignmentTest {
    @Test
    void exposesOnlyToolsAssignedToAgent() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var selected = executor.getToolDefinitions(Set.of("calculate_expression"));

        assertEquals(1, selected.size());
        Map<?, ?> function = (Map<?, ?>) selected.get(0).get("function");
        assertEquals("calculate_expression", function.get("name"));
    }

    @Test
    void emptyAssignmentExposesNoTools() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        assertTrue(executor.getToolDefinitions(Set.of()).isEmpty());
    }

    @Test
    void nullAssignmentPreservesLegacyAllToolsBehavior() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        assertTrue(executor.getToolDefinitions().size() > 1);
        assertEquals(executor.getToolDefinitions().size(), executor.getToolDefinitions(null).size());
    }
}
