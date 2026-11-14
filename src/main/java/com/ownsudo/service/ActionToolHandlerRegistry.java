package com.ownsudo.service;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ActionToolHandlerRegistry {
    private final Map<String, ActionToolHandler> handlers;

    public ActionToolHandlerRegistry(List<ActionToolHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                ActionToolHandler::toolName, Function.identity()));
    }

    public ActionToolHandler require(String toolName) {
        ActionToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            throw new ToolHandlerNotFoundException("No server-side handler is installed for tool: " + toolName);
        }
        return handler;
    }

    public static class ToolHandlerNotFoundException extends RuntimeException {
        public ToolHandlerNotFoundException(String message) { super(message); }
    }
}