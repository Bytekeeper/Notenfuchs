package de.notenfuchs.web;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

/**
 * Clears the local-auth session cookie. A distinct path from {@code /logout} (OIDC's
 * RP-initiated logout, wired unconditionally by quarkus-oidc regardless of tenant-enabled - the
 * two mechanisms' logout handling can't share a route) - see the README's Authentication section
 * for why local and OIDC auth are mutually exclusive per deployment.
 */
@Path("/local-logout")
public class LocalLogoutResource {

    @Inject
    SecurityIdentity identity;

    @POST
    public Response logout() {
        FormAuthenticationMechanism.logout(identity);
        return Response.seeOther(UriBuilder.fromPath("/login").build()).build();
    }
}
