package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import de.notenfuchs.web.LocalAuthTestProfile;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Browser-driven end-to-end coverage for the local-auth login flow through the real rendered
 * login page (see {@code LoginPage/login.html}, {@link de.notenfuchs.web.LoginUiResource}):
 * an unauthenticated visit lands on {@code /login}, a correct password reaches the class list,
 * a wrong password is rejected with an inline error, and logging out (via the nav's "Logout"
 * link, {@code /local-logout}) actually ends the session. {@link de.notenfuchs.web.LoginIT}
 * covers the same flow's raw HTTP semantics (status codes, redirects, cookies).
 *
 * <p>Uses {@link LocalAuthTestProfile} to flip the %test default (blanket permit, OIDC disabled)
 * back to real enforcement. Runs as a Failsafe IT ({@code ./mvnw verify}) for the same reasons as
 * {@link GradeGridE2EIT}: needs a real running app, a browser (quarkus-playwright's Dev Services
 * container), and Postgres.
 */
@QuarkusTest
@TestProfile(LocalAuthTestProfile.class)
@WithPlaywright
class LoginE2EIT {

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    private Page page;

    @BeforeEach
    void openPage() {
        // The injected BrowserContext (and its cookie jar) is shared across this class's test
        // methods - clear it so a session cookie from one login test can't leak into the next
        // and make it look already-authenticated.
        context.clearCookies();
        page = context.newPage();
    }

    private String baseUrl() {
        return "http://host.testcontainers.internal:" + rootUrl.getPort() + "/";
    }

    @Test
    void unauthenticatedVisitRedirectsToLoginPage() {
        page.navigate(baseUrl());

        assertThat(page).hasURL(Pattern.compile(".*/login.*"));
        assertThat(page.locator("input[name='j_username']")).isVisible();
        assertThat(page.locator("input[name='j_password']")).isVisible();
    }

    @Test
    void correctPasswordReachesTheClassList() {
        login(LocalAuthTestProfile.USERNAME, LocalAuthTestProfile.PASSWORD);

        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Klassen"))).isVisible();
        assertThat(page.locator(".user-info")).containsText("Angemeldet als " + LocalAuthTestProfile.USERNAME);
    }

    @Test
    void wrongPasswordShowsErrorAndStaysOnLogin() {
        login(LocalAuthTestProfile.USERNAME, "not-the-password");
        System.out.println("DEBUG URL: " + page.url());
        System.out.println("DEBUG BODY: " + page.content());

        assertThat(page).hasURL(Pattern.compile(".*/login.*error.*"));
        assertThat(page.getByText("Falscher Benutzername oder falsches Passwort.")).isVisible();
    }

    @Test
    void logoutEndsTheSessionAndFurtherNavigationRedirectsToLogin() {
        login(LocalAuthTestProfile.USERNAME, LocalAuthTestProfile.PASSWORD);
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Klassen"))).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Logout")).click();

        page.navigate(baseUrl() + "classes");
        assertThat(page).hasURL(Pattern.compile(".*/login.*"));
    }

    private void login(String username, String password) {
        page.navigate(baseUrl() + "login");
        page.locator("input[name='j_username']").fill(username);
        page.locator("input[name='j_password']").fill(password);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anmelden")).click();
    }
}
