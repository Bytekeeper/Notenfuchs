package de.notenfuchs.web;

import de.notenfuchs.domain.Feedback;
import de.notenfuchs.security.CurrentUser;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The teachers' feedback, for instance admins only ({@link CurrentUser#isInstanceAdmin}).
 * Everyone else gets a 404, like any page they have no access to.
 */
@Path("/admin/feedback")
public class AdminFeedbackResource {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Page types as teachers know them; anything else is shown as stored. */
    private static final Map<String, String> PAGE_NAMES = Map.ofEntries(
            Map.entry("/classes", "Übersicht"),
            Map.entry("/classes/{id}", "Klasse: Fächer & Schüler"),
            Map.entry("/classes/{id}/behavior-grid", "Klasse: Verhaltensnoten"),
            Map.entry("/classes/{id}/overview", "Klasse: Notenübersicht"),
            Map.entry("/classes/{id}/teachers", "Klasse: Lehrkräfte"),
            Map.entry("/classes/{id}/settings", "Klasse: Einstellungen"),
            Map.entry("/subjects/{id}/grid", "Fach: Notenerfassung"),
            Map.entry("/subjects/{id}", "Fach: Leistungen & Gewichtung"),
            Map.entry("/subjects/{id}/teachers", "Fach: Lehrkräfte"),
            Map.entry("/subjects/{id}/grid/students/{id}", "Fach: Seite eines Schülers"));

    @Inject
    CurrentUser currentUser;

    @Inject
    @Location("AdminPage/feedback.html")
    Template feedbackTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        requireInstanceAdmin();
        List<FeedbackView> entries = Feedback.<Feedback>find("order by createdAt desc").page(0, 200).list().stream()
                .map(f -> new FeedbackView(f.id, TIME.format(f.createdAt), PAGE_NAMES.getOrDefault(f.page, f.page),
                        f.page, f.message))
                .toList();
        return currentUser.withUser(feedbackTemplate.data("entries", entries));
    }

    /** "Erledigt": a handled report is removed rather than archived, there is nothing to keep it for. */
    @POST
    @Path("/{feedbackId}/delete")
    @Transactional
    public Response delete(@PathParam("feedbackId") Long feedbackId) {
        requireInstanceAdmin();
        Feedback.deleteById(feedbackId);
        return Redirects.seeOther("/admin/feedback").build();
    }

    private void requireInstanceAdmin() {
        if (!currentUser.isInstanceAdmin()) {
            throw new NotFoundException();
        }
    }

    public record FeedbackView(Long id, String time, String pageName, String pageType, String message) {
    }
}
