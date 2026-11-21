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

    @Test
    void executesCalculatorToolAndReturnsExpectedValue() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var result = executor.executeTool(
                "calculate_expression",
                "{\"expression\":\"(18500 * 0.85) + 3200\"}",
                1);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("18925", ((Map<?, ?>) result.getData()).get("value"));
    }

    @Test
    void rejectsCalculatorCallsWithMissingRequiredArguments() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var result = executor.executeTool("calculate_expression", "{}", 1);

        assertFalse(result.isSuccess());
        assertEquals("SCHEMA_VALIDATION_ERROR", result.getErrorType());
        assertTrue(result.getErrorMessage().contains("expression"));
    }

    @Test
    void taxCreditCodeValidatorUsesTheSameCodeFieldAsThePublishedToolSchema() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var definition = executor.getToolDefinitions().stream()
                .filter(item -> {
                    Map<?, ?> function = (Map<?, ?>) item.get("function");
                    return "verify_tax_credit_code".equals(function.get("name"));
                })
                .findFirst()
                .orElseThrow();
        Map<?, ?> function = (Map<?, ?>) definition.get("function");
        Map<?, ?> parameters = (Map<?, ?>) function.get("parameters");
        Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
        assertTrue(properties.containsKey("code"));

        var result = executor.executeTool("verify_tax_credit_code", "{\"code\":\"123\"}", 1);

        // The call must pass the pre-execution schema gate and reach the handler's
        // business validation, rather than being rejected for a different field name.
        assertFalse(result.isSuccess());
        assertEquals("INTERNAL_EXECUTION_ERROR", result.getErrorType());
        assertTrue(result.getErrorMessage().contains("18 位"));
    }

    @Test
    void preservesDecimalPrecisionForFinancialExpressions() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var result = executor.executeTool(
                "calculate_expression",
                "{\"expression\":\"0.1 + 0.2\"}",
                1);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("0.3", ((Map<?, ?>) result.getData()).get("value"));
    }

    @Test
    void preservesLargeIntegersBeyondDoublePrecision() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var result = executor.executeTool(
                "calculate_expression",
                "{\"expression\":\"9007199254740993 + 1\"}",
                1);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("9007199254740994", ((Map<?, ?>) result.getData()).get("value"));
    }

    @Test
    void rejectsDivisionByZero() {
        ToolExecutor executor = new ToolExecutor();
        executor.initStandardTools();

        var result = executor.executeTool(
                "calculate_expression",
                "{\"expression\":\"1 / 0\"}",
                1);

        assertFalse(result.isSuccess());
        assertEquals("INTERNAL_EXECUTION_ERROR", result.getErrorType());
        assertTrue(result.getErrorMessage().contains("除数不能为零"));
    }

}
