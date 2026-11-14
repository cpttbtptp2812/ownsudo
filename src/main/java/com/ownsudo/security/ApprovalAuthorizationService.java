package com.ownsudo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-secret gate for legacy approval endpoints.
 *
 * This is a transitional single-approver credential, not a replacement for
 * per-user identity, key rotation, or a full Spring Security authorization model.
 */
@Component
public class ApprovalAuthorizationService {

    private final String configuredApiKey;

    public ApprovalAuthorizationService(
            @Value("${app.security.approver-api-key:}") String configuredApiKey) {
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey;
    }

    public boolean isAuthorized(String suppliedApiKey) {
        if (configuredApiKey.isBlank() || suppliedApiKey == null || suppliedApiKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredApiKey.getBytes(StandardCharsets.UTF_8),
                suppliedApiKey.getBytes(StandardCharsets.UTF_8));
    }
}
