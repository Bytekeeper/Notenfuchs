package de.notenfuchs.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Root path just redirects to the class list, the natural starting point of the UI.
 */
@Path("/")
public class RootUiResource {

    @GET
    public Response index() {
        return Response.seeOther(jakarta.ws.rs.core.UriBuilder.fromPath("/classes").build()).build();
    }
}
