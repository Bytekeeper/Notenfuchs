package de.notenfuchs.web;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

final class Redirects {

    private Redirects() {
    }

    /**
     * A 303 with a relative Location. {@code Response.seeOther} makes it absolute from the scheme the
     * app sees, which is http behind a TLS-terminating proxy; the browser would then drop the Secure
     * session cookie.
     */
    static Response.ResponseBuilder seeOther(String path) {
        return Response.status(Response.Status.SEE_OTHER).header(HttpHeaders.LOCATION, path);
    }
}
