package de.notenfuchs.web;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test: behind a TLS-terminating proxy (e.g. a Cloudflare Tunnel) the app sees plain
 * http, so an absolute redirect sent the browser to http://. There the "Secure" session cookie was
 * withheld and the browser landed in a sign-in loop.
 */
@QuarkusTest
class RelativeRedirectIT {

    @TestHTTPResource("/")
    URL rootUrl;

    @Test
    void rootRedirect_isRelative() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(rootUrl.toString())).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(303, response.statusCode());
        assertEquals("/classes", response.headers().firstValue("Location").orElse(null));
    }
}
