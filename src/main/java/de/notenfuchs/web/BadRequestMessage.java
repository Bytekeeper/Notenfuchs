package de.notenfuchs.web;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Sends the German sentence of a {@link BadRequestException} as the response body, so the page
 * can show the teacher why an action was refused (see "Feedback" in docs/ui-concept.md).
 */
public class BadRequestMessage {

    @ServerExceptionMapper
    public Response map(BadRequestException e) {
        if (e.getResponse().hasEntity()) {
            return e.getResponse();
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"))
                .entity(e.getMessage())
                .build();
    }
}
