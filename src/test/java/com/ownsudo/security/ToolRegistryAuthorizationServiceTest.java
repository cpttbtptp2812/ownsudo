package com.ownsudo.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryAuthorizationServiceTest {
    @Test void rejectsMissingConfiguredKey() {
        assertFalse(new ToolRegistryAuthorizationService("").isAuthorized("anything"));
    }
    @Test void rejectsMissingAndWrongSuppliedKey() {
        var service=new ToolRegistryAuthorizationService("secret-admin-key");
        assertFalse(service.isAuthorized(null)); assertFalse(service.isAuthorized("wrong"));
    }
    @Test void acceptsExactAdminKey() {
        assertTrue(new ToolRegistryAuthorizationService("secret-admin-key").isAuthorized("secret-admin-key"));
    }
}
