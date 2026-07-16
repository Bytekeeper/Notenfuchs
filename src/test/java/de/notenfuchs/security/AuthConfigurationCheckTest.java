package de.notenfuchs.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test (no {@code @QuarkusTest}, no DB) for the pure
 * {@link AuthConfigurationCheck#isAuthConfigured} predicate that decides whether a %prod boot
 * should fail fast. See the class Javadoc for why the actual "Quarkus refuses to start" behavior
 * isn't separately exercised end-to-end.
 */
class AuthConfigurationCheckTest {

    @Test
    void configured_whenOnlyPasswordSet() {
        assertTrue(AuthConfigurationCheck.isAuthConfigured("secret", null));
    }

    @Test
    void configured_whenOnlyIssuerSet() {
        assertTrue(AuthConfigurationCheck.isAuthConfigured(null, "https://idp.example.com"));
    }

    @Test
    void configured_whenBothSet() {
        assertTrue(AuthConfigurationCheck.isAuthConfigured("secret", "https://idp.example.com"));
    }

    @Test
    void notConfigured_whenNeitherSet() {
        assertFalse(AuthConfigurationCheck.isAuthConfigured(null, null));
        assertFalse(AuthConfigurationCheck.isAuthConfigured("", "   "));
    }
}
