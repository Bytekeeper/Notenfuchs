package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import de.notenfuchs.domain.Teacher;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Instant;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Browser-driven end-to-end coverage for the "Lehrkräfte" section on {@code
 * ClassPage/detail.html} (backed by {@code fragments/classTeachers.html} and {@code
 * ClassUiResource#addClassTeacher}/{@code #removeClassTeacher}). Complements {@code
 * de.notenfuchs.web.ClassTeacherIT}, which asserts the same behavior at the HTTP/data level - this
 * test instead drives the real select-a-colleague-and-add flow through the UI. A Failsafe IT
 * (./mvnw verify) for the same reasons as {@link ClassDuplicationE2EIT}.
 *
 * <p>Doesn't assert the "no other teacher known yet" empty-directory hint: with no reset between
 * test classes (see {@code ClassDuplicationIT}'s Javadoc on the same point), other tests in the
 * same run leave real, uniquely-named {@link Teacher} rows behind, so a brand new class's
 * available-teachers list isn't reliably empty by the time this test runs. That branch is simple
 * template logic ({@code #if availableTeachers.isEmpty}) already exercised manually during
 * implementation; what's actually worth a browser test is the add/remove interaction itself.
 */
@QuarkusTest
@WithPlaywright
class ClassTeacherE2EIT {

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    private Page page;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    private String baseUrl() {
        return "http://host.testcontainers.internal:" + rootUrl.getPort() + "/";
    }

    @Test
    void addAndRemoveClassTeacherThroughTheRealUi() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-Teacher-Klasse-" + unique;
        String colleagueSubject = "e2e-colleague-" + unique;
        String colleagueLabel = "kollegin-" + unique + "@schule.de";
        seedTeacher(colleagueSubject, colleagueLabel);

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        // Sole owner: shown as "(Sie)", no removal option.
        Locator teachersFragment = page.locator("#class-teachers-fragment");
        assertThat(teachersFragment.locator("tbody tr")).hasCount(1);
        assertThat(teachersFragment.locator("tbody tr").first()).containsText("(Sie)");
        assertThat(teachersFragment.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Entfernen")))
                .hasCount(0);

        // The seeded colleague is selectable and gets added.
        page.locator("#teacherSubject").selectOption(new SelectOption().setValue(colleagueSubject));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Zugriff gewähren")).click();
        assertThat(teachersFragment.locator("tbody tr")).hasCount(2);
        assertThat(teachersFragment.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(colleagueLabel)))
                .isVisible();

        // Now two owners: both rows offer "Entfernen".
        assertThat(teachersFragment.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Entfernen")))
                .hasCount(2);

        // Remove the colleague again - back to a single, non-removable owner.
        Locator colleagueRow = teachersFragment.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(colleagueLabel));
        page.onceDialog(Dialog::accept);
        colleagueRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Entfernen")).click();
        assertThat(teachersFragment.locator("tbody tr")).hasCount(1);
        assertThat(teachersFragment.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Entfernen")))
                .hasCount(0);
    }

    private void seedTeacher(String subject, String email) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.email = email;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
    }
}
