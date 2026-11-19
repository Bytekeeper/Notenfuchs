package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The "Fach wechseln" select in the Fach header: jumps to another Fach and keeps the current tab. */
@QuarkusTest
@WithPlaywright
class SubjectSwitcherE2EIT {

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    private Page page;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @Test
    void switchingFachKeepsTheCurrentTab() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-Klasse-" + unique;
        String first = "E2E-Erstes-" + unique;
        String second = "E2E-Zweites-" + unique;

        page.navigate("http://host.testcontainers.internal:" + rootUrl.getPort() + "/");
        AddPanels.open(page, "+ Klasse anlegen");
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        for (String subject : new String[] {first, second}) {
            page.locator("#subjectName").fill(subject);
            page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();
            assertThat(page.locator("#subject-list-fragment")).containsText(subject);
        }

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(first)).click();
        page.locator(".subject-switcher select").selectOption(new SelectOption().setLabel(className + " – " + second));
        assertThat(page.locator("h1")).containsText(second);
        assertThat(page).hasURL(Pattern.compile(".*/subjects/\\d+/grid$"));

        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Lehrkräfte")).click();
        page.locator(".subject-switcher select").selectOption(new SelectOption().setLabel(className + " – " + first));
        assertThat(page.locator("h1")).containsText(first);
        assertThat(page).hasURL(Pattern.compile(".*/subjects/\\d+/teachers$"));
    }
}
