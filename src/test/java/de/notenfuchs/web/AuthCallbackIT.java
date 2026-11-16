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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: a callback the OIDC mechanism doesn't take over (expired state cookie) used to
 * end in a bare "Resource not found" 404. The %test profile disables the OIDC tenant, which takes
 * the same path as a request without a state cookie.
 */
@QuarkusTest
class AuthCallbackIT {

    @TestHTTPResource("/auth-callback?code=abc&state=xyz")
    URL callbackUrl;

    @Test
    void callbackWithoutStateCookie_showsSignInAgainPage() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(callbackUrl.toString())).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Erneut anmelden"));
    }
}
