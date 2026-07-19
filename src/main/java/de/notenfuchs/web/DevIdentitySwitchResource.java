package de.notenfuchs.web;

import de.notenfuchs.domain.Teacher;
import de.notenfuchs.security.CurrentUser;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

/**
 * %dev-only tool: lets a developer switch which subject {@link CurrentUser#effectiveSubject()}
 * resolves to, by writing {@link CurrentUser#DEV_IDENTITY_COOKIE}. This is what makes it possible
 * to actually "be" e.g. the second teacher {@code DevTeacherSeeder} seeds ("dev-user-2"), rather
 * than just having it exist as a selectable name in someone else's "add a co-owner" dropdown -
 * there is otherwise no way to become a second local identity without a real second OIDC login.
 *
 * <p>Gated by {@code @IfBuildProfile("dev")} - this whole resource, cookie and all, doesn't exist
 * outside %dev. {@link CurrentUser#effectiveSubject()} additionally only ever trusts the cookie
 * when {@link CurrentUser#DEV_IDENTITY_SWITCH_ACTIVE_PROPERTY} is active (also %dev-only) - see
 * that constant's Javadoc for why that's a real security boundary, not just belt-and-suspenders.
 */
@Path("/dev/identity")
@IfBuildProfile("dev")
public class DevIdentitySwitchResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    @Location("DevPage/identity.html")
    Template identityTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show() {
        List<Teacher> teachers = Teacher.listAll();
        return currentUser.withUser(identityTemplate
                .data("teachers", teachers)
                .data("currentSubject", currentUser.effectiveSubject()));
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response switchTo(@FormParam("subject") String subject) {
        boolean clear = subject == null || subject.isBlank();
        NewCookie cookie = new NewCookie.Builder(CurrentUser.DEV_IDENTITY_COOKIE)
                .value(clear ? "" : subject.trim())
                .path("/")
                .maxAge(clear ? 0 : NewCookie.DEFAULT_MAX_AGE)
                .httpOnly(true)
                .build();
        return Response.seeOther(URI.create("/dev/identity")).cookie(cookie).build();
    }
}
