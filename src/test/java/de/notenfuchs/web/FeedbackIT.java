package de.notenfuchs.web;

import de.notenfuchs.domain.Feedback;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The feedback dialog's endpoint: stores the kind of page and the message, nothing else. */
@QuarkusTest
class FeedbackIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void storesPageTypeAndMessage() throws Exception {
        String message = "Der Knopf klemmt " + System.nanoTime();

        assertEquals(204, http.post("/feedback", Map.of("page", "/classes/17/settings", "message", "  " + message + " ")).statusCode());

        Feedback stored = Feedback.find("message", message).firstResult();
        assertEquals("/classes/{id}/settings", stored.page);
    }

    @Test
    void emptyOrTooLongMessagesAreRefused() throws Exception {
        assertEquals(400, http.post("/feedback", Map.of("page", "/classes", "message", "   ")).statusCode());
        assertEquals(400, http.post("/feedback", Map.of("page", "/classes", "message", "x".repeat(2001))).statusCode());
    }
}
