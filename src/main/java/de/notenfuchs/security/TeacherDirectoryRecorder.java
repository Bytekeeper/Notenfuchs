package de.notenfuchs.security;

import de.notenfuchs.domain.Teacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.spi.runtime.AuthenticationSuccessEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Upserts a {@link Teacher} directory row on every successful authentication, so "pick a
 * colleague from a list" UI (see {@code ClassUiResource#addClassTeacher}) has something to offer -
 * a teacher only becomes selectable once they've logged in at least once.
 *
 * <p>{@link AuthenticationSuccessEvent} is fired by Quarkus's own {@code HttpAuthenticator} for
 * every {@code HttpAuthenticationMechanism} alike (local FORM auth and OIDC both go through it),
 * so this needs no per-auth-mode plumbing. Found empirically: it does NOT fire on the {@code
 * POST /j_security_check} login request itself (that's handled by Vert.x's own FORM-auth route,
 * which never calls {@code HttpAuthenticator.attemptAuthentication}) - only on the *next* request
 * that presents the resulting session cookie against a protected resource (in a browser, this is
 * automatic: it's the page the login redirect lands on). It also does NOT fire under the {@code
 * %dev}/{@code %test} profiles' permit-all HTTP policy (no authentication is ever attempted there)
 * - that's fine here rather than something to work around: those profiles only ever have the one
 * fixed "dev-user" identity anyway, so there's no second teacher to select from regardless of
 * whether this fires. See {@link TeacherDirectoryRecorderIT} for how to actually exercise this in
 * a test (a real login via {@code LocalAuthTestProfile}, followed by a request to a protected
 * page - "login" alone is never observable).
 *
 * <p><b>Threading, the hard way (verified empirically against a real login, not assumed)</b>: this
 * event fires from deep inside {@code HttpAuthenticator}'s reactive pipeline, on the Vert.x
 * event-loop thread.
 * <ul>
 *     <li>{@code @Blocking} cannot be placed on an {@code @Observes} method at all - Quarkus's
 *     build-time checks reject it ("not an entrypoint"), so the DB write has to be explicitly
 *     dispatched to a worker thread instead, via {@link ManagedExecutor}.</li>
 *     <li>{@link CurrentUser}'s accessors ({@code email()}/{@code displayName()}/{@code
 *     isAuthenticated()}) are NOT safe to call here even before that dispatch: they read the
 *     request-scoped {@code SecurityIdentity} association, which internally does a blocking {@code
 *     Uni.await()} and throws {@code IllegalStateException("The current thread cannot be
 *     blocked")} on the event loop. Dispatching the read itself onto the {@link ManagedExecutor}
 *     worker thread "fixes" the exception but silently returns the wrong (unauthenticated)
 *     answer - the per-request security context isn't propagated onto that thread the way CDI
 *     request scope normally is for an actual HTTP request. So this reads directly from {@link
 *     AuthenticationSuccessEvent#getSecurityIdentity()} instead (already a concrete, resolved
 *     instance handed to us - no lazy proxy, no association lookup, safe on any thread) via its
 *     {@code userinfo} attribute (the key OIDC's own {@code OidcUtils.setSecurityIdentityUserInfo}
 *     populates it under) rather than going through {@link CurrentUser} at all.</li>
 * </ul>
 */
@ApplicationScoped
public class TeacherDirectoryRecorder {

    private static final Logger LOG = Logger.getLogger(TeacherDirectoryRecorder.class);

    @Inject
    ManagedExecutor managedExecutor;

    void onAuthenticationSuccess(@Observes AuthenticationSuccessEvent event) {
        SecurityIdentity identity = event.getSecurityIdentity();
        String subject = identity.getPrincipal().getName();
        String email = null;
        String displayName = null;
        UserInfo userInfo = identity.getAttribute("userinfo");
        if (userInfo != null) {
            email = blankToNull(userInfo.getString("email"));
            displayName = blankToNull(userInfo.getString("name"));
        }
        if (displayName == null) {
            // Mirrors CurrentUser#displayName()'s final fallback - also what local auth's fixed
            // "lehrer" identity (no UserInfo at all) always ends up as.
            displayName = subject;
        }
        String finalEmail = email;
        String finalDisplayName = displayName;
        managedExecutor.runAsync(() -> record(subject, finalEmail, finalDisplayName))
                .exceptionally(e -> {
                    LOG.warn("Failed to record teacher directory sighting for subject " + subject, e);
                    return null;
                });
    }

    private void record(String subject, String email, String displayName) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = Teacher.find("subject", subject).firstResult();
            Instant now = Instant.now();
            if (teacher == null) {
                teacher = new Teacher();
                teacher.subject = subject;
                teacher.firstSeenAt = now;
                teacher.persist();
            }
            teacher.email = email;
            teacher.displayName = displayName;
            teacher.lastSeenAt = now;
        });
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
