package com.ownsudo.service;

import java.util.Map;

/**
 * A server-side implementation of a tool that the Action Runtime is allowed to execute.
 * Tool metadata alone never makes a tool executable.
 */
public interface ActionToolHandler {
    String toolName();
    Map<String, Object> execute(Map<String, Object> arguments);
}