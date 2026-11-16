package de.notenfuchs.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Only reached when the OIDC mechanism did not take over the callback, i.e. the browser returned
 * without a valid state cookie (it expired, or the sign-in started at the IdP rather than here).
 * Without this page that ends in a bare 404. Links back instead of redirecting, so a browser that
 * blocks cookies doesn't loop between Notenfuchs and the IdP.
 */
@Path("/auth-callback")
public class AuthCallbackUiResource {

    @Inject
    @Location("LoginPage/authCallbackExpired.html")
    Template expiredTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance expired() {
        return expiredTemplate.instance();
    }
}
