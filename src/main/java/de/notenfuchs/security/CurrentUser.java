package de.notenfuchs.security;

import io.quarkus.oidc.IdToken;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
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
 * <p><b>Future per-teacher data scoping:</b> once the domain model gains an "owner"
 * concept (e.g. a {@code Teacher} entity or an {@code ownerSubject} column on
 * {@code SchoolClass}), {@link #subject()} is the value to key that ownership on -
 * it is the stable, unique OIDC subject claim. That is the natural hook point for
 * filtering queries like {@code SchoolClass.list(...)} by the current teacher; no
 * change to this class should be needed to support that, only call sites that use it.
 */
@RequestScoped
public class CurrentUser {

    @Inject
    SecurityIdentity identity;

    // UserInfo requires the "email"/"profile" scopes (requested by default, see
    // quarkus.oidc.authentication.scopes) and a userinfo endpoint on the provider.
    // Injection is lazy/proxied, so this is safe to inject even when unauthenticated
    // or when OIDC is disabled - only touching it would require a live session.
    @Inject
    UserInfo userInfo;

    @Inject
    @IdToken
    JsonWebToken idToken;

    /**
     * True if the current request is associated with an authenticated OIDC session.
     */
    public boolean isAuthenticated() {
        return identity != null && !identity.isAnonymous();
    }

    /**
     * The stable OIDC subject identifier ("sub" claim) for the logged-in user,
     * or empty if not authenticated. This is the right key to use for any future
     * per-teacher data ownership/scoping, rather than email (which can change).
     */
    public Optional<String> subject() {
        if (!isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.ofNullable(identity.getPrincipal() != null ? identity.getPrincipal().getName() : null);
    }

    /**
     * Best-effort display email for the logged-in user, sourced from the OIDC
     * UserInfo endpoint (falls back to the ID token's "email" claim if UserInfo
     * didn't return one, e.g. because the provider only put it in the token).
     */
    public Optional<String> email() {
        if (!isAuthenticated()) {
            return Optional.empty();
        }
        String fromUserInfo = safeUserInfoString("email");
        if (fromUserInfo != null && !fromUserInfo.isBlank()) {
            return Optional.of(fromUserInfo);
        }
        return Optional.ofNullable(safeIdTokenClaim("email"));
    }

    /**
     * Best-effort human-readable name (e.g. for "logged in as X" UI), preferring
     * UserInfo's "name", falling back to the ID token claim, then to the subject.
     */
    public Optional<String> displayName() {
        if (!isAuthenticated()) {
            return Optional.empty();
        }
        String fromUserInfo = safeUserInfoString("name");
        if (fromUserInfo != null && !fromUserInfo.isBlank()) {
            return Optional.of(fromUserInfo);
        }
        String fromToken = safeIdTokenClaim("name");
        if (fromToken != null && !fromToken.isBlank()) {
            return Optional.of(fromToken);
        }
        return subject();
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
