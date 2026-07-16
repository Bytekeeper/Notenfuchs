package de.notenfuchs.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The local-auth login page (see the README's Authentication section). Public - reachable while
 * unauthenticated, via the {@code public} HTTP permission set in application.properties. Renders
 * standalone rather than extending {@code base.html}, since there's no logged-in user yet to show
 * a nav for.
 *
 * <p>The form itself posts straight to Quarkus's built-in {@code /j_security_check} handler
 * ({@code quarkus.http.auth.form.*} in application.properties) - this resource only serves the
 * page, it never processes credentials itself.
 */
@Path("/login")
public class LoginUiResource {

    @Inject
    @Location("LoginPage/login.html")
    Template loginTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login(@QueryParam("error") String error) {
        return loginTemplate.data("error", error != null);
    }
}
