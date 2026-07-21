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
 * SubjectPage/detail.html} (backed by {@code fragments/subjectTeachers.html} and {@code
 * SubjectUiResource#addSubjectTeacher}/{@code #removeSubjectTeacher}). Complements {@code
 * de.notenfuchs.web.SubjectTeacherIT}, which asserts the same behavior (plus the self-service and
 * self-removal-redirect specifics) at the HTTP/data level - this test instead drives the real
 * select-a-colleague-and-add flow through the UI, mirroring {@link ClassTeacherE2EIT} closely. A
 * Failsafe IT (./mvnw verify) for the same reasons as {@link ClassDuplicationE2EIT}.
 *
 * <p>No self-removal-through-the-browser scenario here, for the same reason {@link
 * ClassTeacherE2EIT} has none: it would navigate the actor away mid-test for no additional
 * coverage the HTTP-level IT doesn't already give more precisely (including both redirect-target
 * branches, which need seeded cross-tenant state a browser flow can't easily set up anyway). Also
 * doesn't assert the "no other teacher known yet" empty-directory hint - same reasoning as {@link
 * ClassTeacherE2EIT}: no reset between test classes means a fresh subject's available-teachers
 * list isn't reliably empty by the time this runs.
 */
@QuarkusTest
@WithPlaywright
class SubjectTeacherE2EIT {

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
    void addAndRemoveSubjectTeacherThroughTheRealUi() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-SubjTeacher-Klasse-" + unique;
        String subjectName = "E2E-SubjTeacher-Fach-" + unique;
        String colleagueSubject = "e2e-subj-colleague-" + unique;
        String colleagueLabel = "kollegin-subj-" + unique + "@schule.de";
        seedTeacher(colleagueSubject, colleagueLabel);

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        // Sole teacher: shown as "(Sie)", no removal option.
        Locator teachersFragment = page.locator("#subject-teachers-fragment");
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

        // Now two teachers: both rows offer "Entfernen".
        assertThat(teachersFragment.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Entfernen")))
                .hasCount(2);

        // Remove the colleague again - back to a single, non-removable teacher.
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
