package de.notenfuchs.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Refuses to boot an unauthenticated production instance: if NEITHER local auth
 * ({@code NOTENFUCHS_PASSWORD}) NOR OIDC ({@code OIDC_ISSUER_URL}) is configured, throwing from a
 * {@link StartupEvent} observer aborts Quarkus startup with a clear message instead of silently
 * serving every teacher's grade data to anyone.
 *
 * <p>Only runs in {@link LaunchMode#NORMAL} (i.e. the packaged/prod app) - {@code %dev}/{@code
 * %test} intentionally bypass authentication entirely (see {@code application.properties}), so
 * neither var is expected to be set there.
 *
 * <p>The env-var presence check itself ({@link #isAuthConfigured}) is unit-tested directly;
 * actually exercising "Quarkus refuses to start" end-to-end isn't - that would need a whole
 * separate failing-boot test process, out of proportion for a two-line predicate.
 */
@ApplicationScoped
public class AuthConfigurationCheck {

    void ensureAuthConfigured(@Observes StartupEvent event) {
        if (LaunchMode.current() != LaunchMode.NORMAL) {
            return;
        }
        if (!isAuthConfigured(System.getenv("NOTENFUCHS_PASSWORD"), System.getenv("OIDC_ISSUER_URL"))) {
            throw new IllegalStateException(
                    "Notenfuchs refuses to start unauthenticated: set NOTENFUCHS_PASSWORD (local "
                            + "login) or OIDC_ISSUER_URL/OIDC_CLIENT_ID/OIDC_CLIENT_SECRET (external "
                            + "IdP). See the README's Authentication section.");
        }
    }

    static boolean isAuthConfigured(String rawPassword, String rawIssuer) {
        return LocalAuthConfigSource.isConfigured(rawPassword) || LocalAuthConfigSource.isConfigured(rawIssuer);
    }
}
