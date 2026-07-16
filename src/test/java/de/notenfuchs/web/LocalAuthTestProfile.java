package de.notenfuchs.web;

import de.notenfuchs.security.LocalAuthConfigSource;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Flips the %test default (blanket "permit" policy, OIDC tenant disabled - see
 * application.properties) back to real local-auth enforcement, just for the login-flow tests
 * ({@link LoginIT}, {@link de.notenfuchs.e2e.LoginE2EIT}) that need to actually exercise it.
 *
 * <p>Config overrides from a {@link QuarkusTestProfile} win over every other config source
 * (including profile-scoped application.properties lines and {@link LocalAuthConfigSource}
 * itself), and a distinct test profile makes Quarkus re-augment the app for these two test
 * classes - which matters because {@code quarkus.security.users.embedded.enabled} and {@code
 * quarkus.http.auth.form.enabled} are {@code BUILD_AND_RUN_TIME_FIXED} (see the Javadoc on
 * {@link LocalAuthConfigSource}); they're already baked {@code true} unconditionally though, so
 * what actually needs overriding here is just the enforced policy/mechanism and a known password
 * for the fixed user.
 */
public class LocalAuthTestProfile implements QuarkusTestProfile {

    public static final String USERNAME = LocalAuthConfigSource.FIXED_USERNAME;
    public static final String PASSWORD = "test-lehrer-passwort";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.security.users.embedded.users." + USERNAME, PASSWORD,
                "quarkus.oidc.tenant-enabled", "false",
                "quarkus.http.auth.permission.authenticated.policy", "authenticated",
                "quarkus.http.auth.permission.authenticated.auth-mechanism", "form",
                LocalAuthConfigSource.ACTIVE_PROPERTY, "true");
    }
}
