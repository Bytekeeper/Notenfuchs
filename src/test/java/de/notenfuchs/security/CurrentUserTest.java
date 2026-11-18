package de.notenfuchs.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void effectiveSubject_usesDevUserSubjectOverride_whenConfigured() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.devUserSubject = "colleague-2";

        assertEquals("colleague-2", currentUser.effectiveSubject());
    }

    @Test
    void effectiveSubject_ignoresIdentitySwitchCookie_whenSwitchNotActive() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.devIdentitySwitchActive = false;

        assertEquals(CurrentUser.DEV_USER_SUBJECT, currentUser.effectiveSubject());
    }

    @Test
    void effectiveSubject_fallsBackToDevUserSubject_whenIdentitySwitchActiveButNoRequestContext() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.devIdentitySwitchActive = true;
        // currentVertxRequest stays null, exactly as it does for this plain non-CDI instance -
        // the cookie lookup must tolerate that rather than NPE, and fall back like the switch
        // was never active.

        assertEquals(CurrentUser.DEV_USER_SUBJECT, currentUser.effectiveSubject());
    }

    @Test
    void instanceAdmin_localUserOrAdminGroupOrDevSwitch() {
        java.util.Set<String> none = java.util.Set.of();
        // Local login: the single user runs the instance.
        assertTrue(CurrentUser.instanceAdmin(true, true, none, "notenfuchs-admins", false));
        // OIDC: only members of the admin group.
        assertTrue(CurrentUser.instanceAdmin(false, true, java.util.Set.of("lehrer", "notenfuchs-admins"), "notenfuchs-admins", false));
        assertFalse(CurrentUser.instanceAdmin(false, true, java.util.Set.of("lehrer"), "notenfuchs-admins", true));
        // No login at all (%dev/%test): the config switch decides.
        assertTrue(CurrentUser.instanceAdmin(false, false, none, "notenfuchs-admins", true));
        assertFalse(CurrentUser.instanceAdmin(false, false, none, "notenfuchs-admins", false));
    }
}
