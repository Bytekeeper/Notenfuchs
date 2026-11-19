package de.notenfuchs.web;

import de.notenfuchs.domain.Feedback;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

/** Stores what the feedback dialog (in {@code base.html}) sends; readable only via the database. */
@Path("/feedback")
public class FeedbackResource {

    static final int MAX_MESSAGE_LENGTH = 2000;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response send(@FormParam("page") String page, @FormParam("message") String message) {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException("Nachricht fehlt oder ist zu lang");
        }
        Feedback feedback = new Feedback();
        feedback.page = pageType(page);
        feedback.message = text;
        feedback.createdAt = Instant.now();
        feedback.persist();
        return Response.noContent().build();
    }

    /**
     * Reduces an address to the kind of page: query and fragment dropped, every numeric path
     * segment replaced by {@code {id}}. Done here, not in the browser, so ids can never be stored.
     */
    static String pageType(String path) {
        if (path == null || !path.startsWith("/")) {
            return "unbekannt";
        }
        String withoutQuery = path.split("[?#]", 2)[0];
        String type = withoutQuery.replaceAll("(?<=/)\\d+(?=/|$)", "{id}");
        if (!type.matches("[A-Za-z0-9/_{}-]{1,200}")) {
            return "unbekannt";
        }
        return type;
    }
}
