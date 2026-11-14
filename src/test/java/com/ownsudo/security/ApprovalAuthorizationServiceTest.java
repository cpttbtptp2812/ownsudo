package com.ownsudo.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalAuthorizationServiceTest {

    @Test
    void rejectsWhenCredentialsAreNotConfigured() {
        ApprovalAuthorizationService service = new ApprovalAuthorizationService("", "");

        assertFalse(service.isAuthorized("anything"));
        assertFalse(service.isAuthorized(null));
    }

    @Test
    void rejectsWhenApproverIdentityIsMissing() {
        ApprovalAuthorizationService service = new ApprovalAuthorizationService("secret", "");

        assertFalse(service.isAuthorized("secret"));
    }

    @Test
    void acceptsOnlyTheConfiguredKeyWhenApproverIsConfigured() {
        ApprovalAuthorizationService service = new ApprovalAuthorizationService("secret", "security-reviewer");

        assertTrue(service.isAuthorized("secret"));
        assertFalse(service.isAuthorized("wrong"));
        assertFalse(service.isAuthorized(""));
        assertFalse(service.isAuthorized(null));
    }
}
