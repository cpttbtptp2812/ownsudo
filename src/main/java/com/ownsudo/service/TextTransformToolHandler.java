package com.ownsudo.service;

import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;

@Component
public class TextTransformToolHandler implements ActionToolHandler {
    @Override
    public String toolName() { return "text.transform"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments) {
        Object rawText = arguments.get("text");
        Object rawOperation = arguments.get("operation");
        if (!(rawText instanceof String text) || text.length() > 10000) {
            throw new IllegalArgumentException("text must be a string with at most 10000 characters");
        }
        if (!(rawOperation instanceof String operation)) {
            throw new IllegalArgumentException("operation must be a string");
        }
        String transformed = switch (operation.toLowerCase(Locale.ROOT)) {
            case "uppercase" -> text.toUpperCase(Locale.ROOT);
            case "lowercase" -> text.toLowerCase(Locale.ROOT);
            case "trim" -> text.trim();
            case "reverse" -> new StringBuilder(text).reverse().toString();
            default -> throw new IllegalArgumentException("operation must be uppercase, lowercase, trim, or reverse");
        };
        return Map.of("text", transformed, "operation", operation.toLowerCase(Locale.ROOT));
    }
}