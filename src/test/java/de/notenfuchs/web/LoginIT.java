package de.notenfuchs.web;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the local-auth login flow (see the README's Authentication section):
 * an unauthenticated request redirects to {@code /login}, the correct password logs in (session
 * cookie granting access to a protected page), and a wrong password is rejected (bounced back to
 * {@code /login?error}, no usable session). Uses {@link LocalAuthTestProfile} to flip the %test
 * default (blanket permit, OIDC disabled) back to real enforcement - see that class's Javadoc.
 *
 * <p>Drives the real endpoints over plain HTTP (no RestAssured in this project's dependencies -
 * same reasoning as {@link ClassDuplicationIT}), redirects disabled so each hop's
 * status/Location/Set-Cookie can be asserted directly. {@link de.notenfuchs.e2e.LoginE2EIT}
 * covers the same flow through a real browser against the rendered login form.
 */
@QuarkusTest
@TestProfile(LocalAuthTestProfile.class)
class LoginIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void unauthenticatedRequestRedirectsToLogin() throws Exception {
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/classes")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(302, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("").contains("/login"),
                "expected a redirect to the login page, got " + response.headers().firstValue("Location"));
    }

    @Test
    void correctPasswordLogsInAndGrantsAccess() throws Exception {
        HttpResponse<Void> loginResponse = postCredentials(LocalAuthTestProfile.USERNAME, LocalAuthTestProfile.PASSWORD);
        assertEquals(302, loginResponse.statusCode());

        String sessionCookie = extractCookie(loginResponse, "quarkus-credential");
        assertNotNull(sessionCookie, "expected a session cookie to be set on successful login");

        HttpResponse<String> protectedPage = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/classes"))
                        .header("Cookie", sessionCookie)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, protectedPage.statusCode());
        assertTrue(protectedPage.body().contains("Klassen"));
    }

    @Test
    void wrongPasswordIsRejectedAndGrantsNoAccess() throws Exception {
        HttpResponse<Void> loginResponse = postCredentials(LocalAuthTestProfile.USERNAME, "not-the-password");

        assertEquals(302, loginResponse.statusCode());
        assertTrue(loginResponse.headers().firstValue("Location").orElse("").contains("/login"),
                "expected a redirect back to the login page on a failed login");

        HttpResponse<Void> protectedPage = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/classes")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, protectedPage.statusCode());
        assertTrue(protectedPage.headers().firstValue("Location").orElse("").contains("/login"));
    }

    private HttpResponse<Void> postCredentials(String username, String password) throws Exception {
        String body = "j_username=" + urlEncode(username) + "&j_password=" + urlEncode(password);
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/j_security_check"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static String extractCookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(name + "="))
                .map(header -> header.split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String baseUrl() {
        String url = rootUrl.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
