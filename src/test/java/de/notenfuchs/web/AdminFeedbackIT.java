package de.notenfuchs.web;

import de.notenfuchs.domain.Feedback;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The feedback list for instance admins (in %test the dev user is one, see application.properties). */
@QuarkusTest
class AdminFeedbackIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void adminSeesFeedbackWithReadablePageAndCanMarkItDone() throws Exception {
        String message = "Export bricht ab " + System.nanoTime();
        http.post("/feedback", Map.of("page", "/subjects/5/grid", "message", message));
        Long id = Feedback.<Feedback>find("message", message).firstResult().id;

        String html = http.get("/admin/feedback").body();
        assertTrue(html.contains(message));
        assertTrue(html.contains("Fach: Notenerfassung"));
        assertTrue(http.get("/classes").body().contains("href=\"/admin/feedback\""));

        assertEquals(303, http.post("/admin/feedback/" + id + "/delete", Map.of()).statusCode());
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> Feedback.count("id", id)));
    }
}
