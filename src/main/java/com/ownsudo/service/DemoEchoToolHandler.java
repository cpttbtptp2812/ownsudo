package com.ownsudo.service;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class DemoEchoToolHandler implements ActionToolHandler {
    @Override
    public String toolName() { return "demo.echo"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments) {
        Object value = arguments.get("message");
        if (!(value instanceof String message) || message.length() > 4000) {
            throw new IllegalArgumentException("message must be a string with at most 4000 characters");
        }
        return Map.of("message", message, "characterCount", message.length());
    }
}