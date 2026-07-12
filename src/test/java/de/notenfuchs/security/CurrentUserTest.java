package de.notenfuchs.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test (no {@code @QuarkusTest}, no DB) for {@link CurrentUser}'s
 * dev/test fallback behavior. Constructed directly rather than via CDI - its {@code identity}
 * field stays {@code null}, matching the unauthenticated state {@code isAuthenticated()} already
 * has to tolerate (see its Javadoc), which is exactly what %dev/%test looks like since OIDC is
 * disabled there (see application.properties).
 */
class CurrentUserTest {

    @Test
    void effectiveSubject_fallsBackToDevUser_whenUnauthenticated() {
        CurrentUser currentUser = new CurrentUser();

        assertTrue(currentUser.subject().isEmpty());
        assertEquals(CurrentUser.DEV_USER_SUBJECT, currentUser.effectiveSubject());
    }
}
