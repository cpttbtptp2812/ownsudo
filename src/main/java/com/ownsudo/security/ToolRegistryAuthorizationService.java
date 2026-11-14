package com.ownsudo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class ToolRegistryAuthorizationService {
    private final String configuredKey;
    public ToolRegistryAuthorizationService(@Value("${app.security.tool-admin-api-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey;
    }
    public boolean isAuthorized(String suppliedKey) {
        if (configuredKey.isBlank() || suppliedKey == null || suppliedKey.isBlank()) return false;
        return MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8));
    }
}
