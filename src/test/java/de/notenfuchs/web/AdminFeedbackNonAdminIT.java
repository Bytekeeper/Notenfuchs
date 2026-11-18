package de.notenfuchs.web;

import de.notenfuchs.domain.Feedback;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Someone who isn't an instance admin neither sees nor reaches the feedback list. */
@QuarkusTest
@TestProfile(AdminFeedbackNonAdminIT.NotAdmin.class)
class AdminFeedbackNonAdminIT {

    public static class NotAdmin implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("notenfuchs.dev-instance-admin", "false");
        }
    }

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void feedbackListIsHiddenAndUnreachable() throws Exception {
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            Feedback feedback = new Feedback();
            feedback.page = "/classes";
            feedback.message = "bleibt";
            feedback.createdAt = Instant.now();
            feedback.persist();
            return feedback.id;
        });

        assertEquals(404, http.get("/admin/feedback").statusCode());
        assertEquals(404, http.post("/admin/feedback/" + id + "/delete", Map.of()).statusCode());
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> Feedback.count("id", id)));
        assertFalse(http.get("/classes").body().contains("/admin/feedback"));
    }
}
