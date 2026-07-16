package de.notenfuchs.security;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Derives every "which auth mode is active" config property from a single signal - whether
 * {@code NOTENFUCHS_PASSWORD} is set - so an operator only ever sets ONE env var (that password,
 * or the {@code OIDC_ISSUER_URL} family) rather than a separate mode flag. See the README's
 * Authentication section for the full local-vs-OIDC decision flow.
 *
 * <p>{@code quarkus.security.users.embedded.enabled} and {@code quarkus.http.auth.form.enabled}
 * are {@code BUILD_AND_RUN_TIME_FIXED} (they gate CDI bean registration at augmentation time), so
 * they can't be toggled per-deployment by a runtime-only source like this one - they're just left
 * permanently {@code true} in {@code application.properties}. What this source CAN drive at true
 * runtime (both are genuinely {@code RUN_TIME}-phase properties):
 * <ul>
 *     <li>{@code quarkus.security.users.embedded.users.lehrer} - the fixed local user's password.
 *     When {@code NOTENFUCHS_PASSWORD} is unset, this is a fresh random value (never the OIDC
 *     issuer's problem, never a blank/guessable one) generated once per process start, so the
 *     always-registered embedded realm simply has no crackable credential to match.</li>
 *     <li>{@code quarkus.oidc.tenant-enabled} - forced {@code false} when local auth is active, so
 *     OIDC's mechanism stops participating entirely (see the Javadoc on
 *     {@link CurrentUser#effectiveSubject()}'s callers / the README) - never active at the same
 *     time as local auth.</li>
 *     <li>{@code quarkus.http.auth.permission.authenticated.auth-mechanism} - pinned to
 *     {@code form} or {@code code} (the literal scheme name Quarkus's OIDC code-flow mechanism
 *     registers under - see {@code OidcConstants.CODE_FLOW_CODE} - NOT "oidc"). Both the embedded
 *     realm's Form mechanism and OIDC's mechanism are always structurally present in the built
 *     app, so without this pin they'd race to answer an unauthenticated request's challenge;
 *     pinning makes exactly one of them authoritative for the catch-all {@code authenticated}
 *     permission set.</li>
 *     <li>{@link #ACTIVE_PROPERTY} - a plain boolean flag mirroring the decision above, purely so
 *     other code (e.g. {@link CurrentUser#localAuthActive()}, for picking the right logout link)
 *     can read the SAME resolved decision through normal config injection instead of re-reading
 *     {@code NOTENFUCHS_PASSWORD} independently, which would disagree with this class under a
 *     test profile that overrides the derived properties directly without setting the env var.</li>
 * </ul>
 */
public class LocalAuthConfigSource implements ConfigSource {

    /** The one fixed local-login username; change this literal + rebuild for a different one. */
    public static final String FIXED_USERNAME = "lehrer";

    /** See the class Javadoc's last bullet. */
    public static final String ACTIVE_PROPERTY = "notenfuchs.local-auth.active";

    private static final int ORDINAL = 275;

    // Generated once per JVM start, never logged/exposed - closes the "blank/predictable
    // password" hole for the always-registered embedded realm when local auth isn't in use.
    private static final String RANDOM_FALLBACK_PASSWORD = UUID.randomUUID().toString();

    @Override
    public Map<String, String> getProperties() {
        return deriveOverrides(System.getenv("NOTENFUCHS_PASSWORD"));
    }

    @Override
    public Set<String> getPropertyNames() {
        return getProperties().keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() {
        return "notenfuchs-local-auth";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    static boolean isConfigured(String rawPassword) {
        return rawPassword != null && !rawPassword.isBlank();
    }

    static Map<String, String> deriveOverrides(String rawPassword) {
        Map<String, String> overrides = new HashMap<>();
        boolean localAuthActive = isConfigured(rawPassword);
        overrides.put("quarkus.security.users.embedded.users." + FIXED_USERNAME,
                localAuthActive ? rawPassword : RANDOM_FALLBACK_PASSWORD);
        overrides.put(ACTIVE_PROPERTY, Boolean.toString(localAuthActive));
        if (localAuthActive) {
            overrides.put("quarkus.oidc.tenant-enabled", "false");
            overrides.put("quarkus.http.auth.permission.authenticated.auth-mechanism", "form");
        } else {
            overrides.put("quarkus.http.auth.permission.authenticated.auth-mechanism", "code");
        }
        return overrides;
    }
}
