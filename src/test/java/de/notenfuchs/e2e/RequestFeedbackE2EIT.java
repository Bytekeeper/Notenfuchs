package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The shared htmx feedback in notenfuchs.js: a refused request shows the server's sentence below its form. */
@QuarkusTest
@WithPlaywright
class RequestFeedbackE2EIT {

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    @Test
    void aRefusedAddShowsTheReasonAndTheButtonWorksAgain() {
        Page page = context.newPage();
        String className = "E2E-Feedback-" + System.nanoTime();
        page.navigate("http://host.testcontainers.internal:" + rootUrl.getPort() + "/");
        AddPanels.open(page, "+ Klasse anlegen");
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        // Any 400 with a sentence; the real refusals are covered by the HTTP-level ITs.
        page.route("**/classes/*/students", route -> {
            if ("POST".equals(route.request().method())) {
                route.fulfill(new Route.FulfillOptions().setStatus(400)
                        .setContentType("text/plain; charset=UTF-8").setBody("Test: so nicht"));
            } else {
                route.resume();
            }
        });
        page.locator("#studentName").fill("Anna");
        Locator add = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen"));
        add.click();

        assertThat(page.getByRole(AriaRole.ALERT)).hasText("Test: so nicht");
        assertThat(add).isEnabled();

        // The next try removes the old message.
        page.unrouteAll();
        add.click();
        assertThat(page.locator("#student-list-fragment")).containsText("Anna");
        assertThat(page.locator(".request-error")).hasCount(0);
    }
}
