package de.notenfuchs.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test (no {@code @QuarkusTest}, no DB) for the pure
 * {@link LocalAuthConfigSource#deriveOverrides} decision logic - actual env var reading
 * ({@code System.getenv}) is a one-line delegation not worth testing separately.
 */
class LocalAuthConfigSourceTest {

    @Test
    void isConfigured_falseForNullOrBlank() {
        assertFalse(LocalAuthConfigSource.isConfigured(null));
        assertFalse(LocalAuthConfigSource.isConfigured(""));
        assertFalse(LocalAuthConfigSource.isConfigured("   "));
    }

    @Test
    void isConfigured_trueForNonBlank() {
        assertTrue(LocalAuthConfigSource.isConfigured("secret"));
    }

    @Test
    void deriveOverrides_whenPasswordSet_pinsFormAndDisablesOidc() {
        Map<String, String> overrides = LocalAuthConfigSource.deriveOverrides("s3cr3t");

        assertEquals("s3cr3t", overrides.get("quarkus.security.users.embedded.users.lehrer"));
        assertEquals("false", overrides.get("quarkus.oidc.tenant-enabled"));
        assertEquals("form", overrides.get("quarkus.http.auth.permission.authenticated.auth-mechanism"));
        assertEquals("true", overrides.get(LocalAuthConfigSource.ACTIVE_PROPERTY));
    }

    @Test
    void deriveOverrides_whenPasswordUnset_pinsOidcCodeFlowAndLeavesTenantEnabledAlone() {
        Map<String, String> overrides = LocalAuthConfigSource.deriveOverrides(null);

        assertEquals("code", overrides.get("quarkus.http.auth.permission.authenticated.auth-mechanism"));
        assertEquals("false", overrides.get(LocalAuthConfigSource.ACTIVE_PROPERTY));
        assertFalse(overrides.containsKey("quarkus.oidc.tenant-enabled"),
                "must not force OIDC off when local auth isn't configured - the %dev/%test bypass "
                        + "or the real deployment's own OIDC config must be the only say in this");
    }

    @Test
    void deriveOverrides_whenPasswordUnset_embeddedUserGetsAnUnguessableRandomPassword() {
        Map<String, String> first = LocalAuthConfigSource.deriveOverrides(null);
        Map<String, String> second = LocalAuthConfigSource.deriveOverrides("");

        String randomPassword = first.get("quarkus.security.users.embedded.users.lehrer");
        assertFalse(randomPassword == null || randomPassword.isBlank(),
                "an empty/blank fallback would let a blank password field log in");
        // Stable across calls within the same process (cached once), not tied to the blank
        // input string used to trigger it.
        assertEquals(randomPassword, second.get("quarkus.security.users.embedded.users.lehrer"));
        assertNotEquals("s3cr3t", randomPassword);
    }
}
