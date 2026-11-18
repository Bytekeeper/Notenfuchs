package de.notenfuchs.security;

import io.quarkus.oidc.IdToken;
import io.quarkus.oidc.UserInfo;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Small facade over the authenticated user's identity, so REST resources and
 * (later) Qute templates don't need to know anything about OIDC/JWT internals.
 *
 * <p>Backed by the request-scoped {@link SecurityIdentity} that {@code quarkus-oidc}
 * populates for the "web-app" application type after a successful authorization-code
 * login. When OIDC is disabled (see {@code %dev.quarkus.oidc.enabled=false} /
 * {@code %test.quarkus.oidc.enabled=false} in {@code application.properties}),
 * {@link #isAuthenticated()} simply returns {@code false} and the other accessors
 * return empty values - callers must be null/empty-safe.
 *
 * <p><b>Per-teacher data scoping:</b> {@code ClassTeacher.teacherSubject}/
 * {@code SubjectTeacher.teacherSubject} are keyed on {@link #subject()} - it is the stable,
 * unique OIDC subject claim. {@link #effectiveSubject()}
 * is the method call sites should actually use for ownership checks (see
 * {@link de.notenfuchs.security.OwnershipGuard}), since it also covers the %dev/%test bypass.
 */
@RequestScoped
public class CurrentUser {

    /**
     * Config property name for overriding {@link #DEV_USER_SUBJECT} - see
     * {@code application.properties} (env var {@code DEV_USER_SUBJECT}, %dev-scoped only, never
     * %test: a stray value in a developer's shell must never change what deterministic test
     * fixtures assume the fixed %test subject is).
     */
    public static final String DEV_USER_SUBJECT_PROPERTY = "notenfuchs.dev-user-subject";

    /**
     * Fixed placeholder subject used by {@link #effectiveSubject()} when there is no
     * authenticated session - i.e. in the %dev/%test profiles, where the OIDC tenant is
     * disabled (see {@code application.properties}) and {@link #subject()} is always empty.
     * Keeps ownership working locally/in tests without a real IdP, and is also what the
     * V2 migration backfills pre-existing {@code SchoolClass} rows to. Overridable in %dev only
     * via {@link #DEV_USER_SUBJECT_PROPERTY} - see {@link #devUserSubject}.
     */
    public static final String DEV_USER_SUBJECT = "dev-user";

    /**
     * Config property gating the dev-only identity switcher ({@code web/DevIdentitySwitchResource},
     * {@code @IfBuildProfile("dev")}) - see {@link #effectiveSubject()}. Defaults to {@code false}
     * everywhere; only {@code %dev} in {@code application.properties} turns it on. This is a real
     * security boundary, not just a UX default: without it, a client-supplied cookie would let
     * anyone impersonate any subject, so it must be structurally impossible to read outside %dev
     * regardless of auth-policy edge cases - not merely "the switcher endpoint doesn't exist there".
     */
    public static final String DEV_IDENTITY_SWITCH_ACTIVE_PROPERTY = "notenfuchs.dev-identity-switch.active";

    /** Cookie the dev-only identity switcher reads/writes - see {@link #DEV_IDENTITY_SWITCH_ACTIVE_PROPERTY}. */
    public static final String DEV_IDENTITY_COOKIE = "notenfuchs-dev-user";

    @Inject
    SecurityIdentity identity;

    @Inject
    CurrentVertxRequest currentVertxRequest;

    @Inject
    @ConfigProperty(name = DEV_IDENTITY_SWITCH_ACTIVE_PROPERTY, defaultValue = "false")
    boolean devIdentitySwitchActive;

    /**
     * The resolved dev-mode subject {@link #effectiveSubject()} falls back to - {@link
     * #DEV_USER_SUBJECT} by default, or the {@link #DEV_USER_SUBJECT_PROPERTY} override under
     * %dev. Initialized inline (not just via {@code defaultValue}) so a plain {@code new
     * CurrentUser()} - constructed outside CDI, as {@code CurrentUserTest} does - still behaves
     * correctly without ever going through injection.
     */
    @Inject
    @ConfigProperty(name = DEV_USER_SUBJECT_PROPERTY, defaultValue = DEV_USER_SUBJECT)
    String devUserSubject = DEV_USER_SUBJECT;

    // UserInfo requires the "profile" scope (requested by default, see
    // quarkus.oidc.authentication.scopes) and a userinfo endpoint on the provider.
    // Injection is lazy/proxied, so this is safe to inject even when unauthenticated
    // or when OIDC is disabled - only touching it would require a live session.
    @Inject
    UserInfo userInfo;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    @ConfigProperty(name = LocalAuthConfigSource.ACTIVE_PROPERTY, defaultValue = "false")
    boolean localAuthActive;

    @Inject
    @ConfigProperty(name = "notenfuchs.admin-group", defaultValue = "notenfuchs-admins")
    String adminGroup;

    @Inject
    @ConfigProperty(name = "notenfuchs.dev-instance-admin", defaultValue = "false")
    boolean devInstanceAdmin;

    // Provider-specific (Pocket ID, Keycloak, ... each use a different path) and not part of OIDC
    // discovery, so it can't be derived from the issuer URL.
    @Inject
    @ConfigProperty(name = "notenfuchs.oidc.account-url")
    Optional<String> oidcAccountUrl = Optional.empty();

    /**
     * True if the current request is associated with an authenticated OIDC session.
     */
    public boolean isAuthenticated() {
        return identity != null && !identity.isAnonymous();
    }

    /**
     * The stable OIDC subject identifier ("sub" claim) for the logged-in user,
     * or empty if not authenticated. This is the right key to use for any future
     * per-teacher data ownership/scoping, rather than a name (which can change).
     */
    public Optional<String> subject() {
        if (!isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.ofNullable(identity.getPrincipal() != null ? identity.getPrincipal().getName() : null);
    }

    /**
     * The subject to use for ownership checks: {@link #subject()} if authenticated, otherwise -
     * only when {@link #DEV_IDENTITY_SWITCH_ACTIVE_PROPERTY} is active (%dev only) - whatever
     * subject {@code web/DevIdentitySwitchResource} last wrote to the {@link #DEV_IDENTITY_COOKIE}
     * cookie, so a developer can actually "be" a second local identity (e.g. to test the add/
     * remove-co-owner flow both ways) without a real second OIDC login; otherwise the plain
     * {@link #devUserSubject} placeholder ({@link #DEV_USER_SUBJECT} by default, or its %dev-only
     * env-var override). Every call site that scopes data by owning teacher (see {@link
     * OwnershipGuard}) should go through this, not {@link #subject()} directly, so local dev/test
     * (OIDC disabled) still gets consistent ownership.
     */
    public String effectiveSubject() {
        Optional<String> real = subject();
        if (real.isPresent()) {
            return real.get();
        }
        if (devIdentitySwitchActive) {
            String switched = devIdentityCookieValue();
            if (switched != null && !switched.isBlank()) {
                return switched;
            }
        }
        return devUserSubject;
    }

    private String devIdentityCookieValue() {
        RoutingContext ctx = currentVertxRequest != null ? currentVertxRequest.getCurrent() : null;
        if (ctx == null) {
            return null;
        }
        Cookie cookie = ctx.request().getCookie(DEV_IDENTITY_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }

    /**
     * True when local built-in auth (see {@link LocalAuthConfigSource}) is the active mode for
     * this deployment rather than OIDC. Reads the resolved {@link LocalAuthConfigSource#ACTIVE_PROPERTY}
     * config value - not {@code NOTENFUCHS_PASSWORD} directly - so this agrees with whatever
     * actually decided the active auth-mechanism, including under a test profile that overrides
     * the derived properties without setting the env var itself. Used purely to pick the right
     * logout link in the nav (a local session clears via {@code /local-logout}, an OIDC one via
     * {@code /logout}); has no bearing on authentication or ownership.
     */
    public boolean localAuthActive() {
        return localAuthActive;
    }

    /** Whether the viewer runs this instance (e.g. may read teachers' feedback). */
    public boolean isInstanceAdmin() {
        boolean authenticated = isAuthenticated();
        return instanceAdmin(localAuthActive, authenticated,
                authenticated ? identity.getRoles() : java.util.Set.of(), adminGroup, devInstanceAdmin);
    }

    /**
     * Local login has a single user, who is the one running the instance. With OIDC it's whoever
     * the login provider puts into the admin group. Without any login (%dev/%test) it's a config
     * switch, since there are no groups to read.
     */
    static boolean instanceAdmin(boolean localAuth, boolean authenticated, java.util.Set<String> roles,
                                 String adminGroup, boolean devFallback) {
        if (!authenticated) {
            return devFallback;
        }
        return localAuth || roles.contains(adminGroup);
    }

    /**
     * Best-effort human-readable name (e.g. for "logged in as X" UI): the "name" claim, then
     * "preferred_username" (both from the "profile" scope, UserInfo first, then the ID token),
     * then the subject.
     */
    public Optional<String> displayName() {
        if (!isAuthenticated()) {
            return Optional.empty();
        }
        for (String claim : new String[] {"name", "preferred_username"}) {
            String fromUserInfo = safeUserInfoString(claim);
            if (fromUserInfo != null && !fromUserInfo.isBlank()) {
                return Optional.of(fromUserInfo);
            }
            String fromToken = safeIdTokenClaim(claim);
            if (fromToken != null && !fromToken.isBlank()) {
                return Optional.of(fromToken);
            }
        }
        return subject();
    }

    /**
     * Decorates a Qute {@link TemplateInstance} with the template globals every
     * server-rendered page needs (nav auth state, display name, which logout route to
     * use) - every {@code *UiResource}/{@code *GridResource} needs exactly this triplet,
     * so it lives here once instead of as a copy-pasted private method in each.
     */
    public TemplateInstance withUser(TemplateInstance instance) {
        return instance
                .data("currentUserAuthenticated", isAuthenticated())
                .data("currentUserDisplayName", displayName().orElse(""))
                .data("localAuthActive", localAuthActive())
                .data("currentUserInstanceAdmin", isInstanceAdmin())
                .data("oidcAccountUrl", localAuthActive() ? "" : oidcAccountUrl.orElse(""))
                .data("devIdentitySwitchActive", devIdentitySwitchActive)
                .data("devEffectiveSubject", effectiveSubject());
    }

    private String safeUserInfoString(String propertyName) {
        try {
            return userInfo != null ? userInfo.getString(propertyName) : null;
        } catch (Exception e) {
            // UserInfo proxy throws if there is no active OIDC session (e.g. anonymous
            // request that slipped through, or OIDC disabled) - treat as "unavailable".
            return null;
        }
    }

    private String safeIdTokenClaim(String claimName) {
        try {
            return idToken != null ? idToken.getClaim(claimName) : null;
        } catch (Exception e) {
            // Same rationale as safeUserInfoString: the injected proxy throws when
            // there is no active OIDC token/session to delegate to.
            return null;
        }
    }
}
