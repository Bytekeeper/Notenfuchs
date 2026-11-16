package de.notenfuchs.security;

import de.notenfuchs.domain.Teacher;
import de.notenfuchs.web.LocalAuthTestProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for {@link TeacherDirectoryRecorder}: a real login must eventually produce
 * (or refresh) a {@link Teacher} row for the logged-in subject. Uses {@link LocalAuthTestProfile}
 * for the same reason {@link de.notenfuchs.web.LoginIT} does - the normal %test profile relaxes
 * the HTTP policy to permit, so no authentication (and therefore no {@code
 * AuthenticationSuccessEvent}) is ever attempted at all; this can't be exercised any other way.
 *
 * <p><b>Important, found empirically</b>: {@code AuthenticationSuccessEvent} does NOT fire on the
 * {@code POST /j_security_check} login request itself - that request is handled by Vert.x's own
 * FORM-auth route, which establishes the session and redirects without going through {@code
 * HttpAuthenticator.attemptAuthentication}. The event only fires on the *next* request that
 * presents the resulting session cookie against a protected resource (in the browser, this is the
 * page the login redirect lands on - automatic; here, it must be done explicitly, exactly like
 * {@link de.notenfuchs.web.LoginIT#correctPasswordLogsInAndGrantsAccess} already does to prove the
 * cookie grants access at all). So each round in this test is "login, then hit a protected page
 * with the session cookie" - "login" alone would never observe the recorder at all.
 *
 * <p>The recorder's DB write is deliberately fire-and-forget (see its Javadoc for why - the event
 * fires on the Vert.x event-loop thread, which can't block), so this test polls briefly rather
 * than asserting immediately after the request returns. The "lehrer" row is deleted first: V4
 * seeds it, so otherwise only the update path would run, and the insert path for a brand-new
 * teacher once failed on every OIDC login unnoticed.
 */
@QuarkusTest
@TestProfile(LocalAuthTestProfile.class)
class TeacherDirectoryRecorderIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void loginThenProtectedRequestRecordsAndRefreshesTeacherDirectoryRow() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> Teacher.delete("subject", LocalAuthTestProfile.USERNAME));

        Instant beforeFirstRound = Instant.now();
        loginAndVisitProtectedPage();
        Teacher afterFirstRound = awaitTeacherSeenAfter(beforeFirstRound);
        assertFalse(afterFirstRound.firstSeenAt.isBefore(beforeFirstRound), "expected a newly inserted row");
        Instant firstSeenAt = afterFirstRound.firstSeenAt;

        Instant beforeSecondRound = Instant.now();
        loginAndVisitProtectedPage();
        Teacher afterSecondRound = awaitTeacherSeenAfter(beforeSecondRound);

        assertEquals(firstSeenAt, afterSecondRound.firstSeenAt);
        assertTrue(afterSecondRound.lastSeenAt.isAfter(afterFirstRound.lastSeenAt));
    }

    private void loginAndVisitProtectedPage() throws Exception {
        String body = "j_username=" + urlEncode(LocalAuthTestProfile.USERNAME)
                + "&j_password=" + urlEncode(LocalAuthTestProfile.PASSWORD);
        HttpResponse<Void> loginResponse = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/j_security_check"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, loginResponse.statusCode());
        String sessionCookie = extractCookie(loginResponse, "quarkus-credential");
        assertNotNull(sessionCookie, "expected a session cookie to be set on successful login");

        HttpResponse<Void> protectedPage = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/classes"))
                        .header("Cookie", sessionCookie)
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, protectedPage.statusCode());
    }

    /**
     * Polls for up to 5s - the recorder's write happens asynchronously, off the request thread.
     * Each attempt runs in its own fresh transaction ({@link QuarkusTransaction#requiringNew()}),
     * not a plain {@code Teacher.find(...)} in a loop: found empirically that repeated Panache
     * reads from the same test-method-scoped persistence context return the same cached (stale)
     * entity instance instead of re-querying the database, so without this the loop would never
     * observe a concurrent write made by the recorder on a different thread.
     */
    private Teacher awaitTeacherSeenAfter(Instant threshold) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Teacher teacher = QuarkusTransaction.requiringNew()
                    .call(() -> Teacher.<Teacher>find("subject", LocalAuthTestProfile.USERNAME).firstResult());
            if (teacher != null && !teacher.lastSeenAt.isBefore(threshold)) {
                return teacher;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Teacher row for " + LocalAuthTestProfile.USERNAME
                + " was not recorded/refreshed within 5s of " + threshold);
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
