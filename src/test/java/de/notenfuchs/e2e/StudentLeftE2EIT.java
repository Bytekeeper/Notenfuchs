package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Marking a student as "ausgetreten" on the class page and taking them back, through the real
 * htmx buttons, and what that does to the grade table. The server rules are in {@code StudentLeftIT}.
 */
@QuarkusTest
@WithPlaywright
class StudentLeftE2EIT {

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
    void studentWhoLeftMovesToAReadOnlySectionAndCanBeTakenBack() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-Klasse-" + unique;
        String subjectName = "E2E-Fach-" + unique;
        String leaver = "E2E-Geht-" + unique;

        page.navigate("http://host.testcontainers.internal:" + rootUrl.getPort() + "/");
        AddPanels.open(page, "+ Klasse anlegen");
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        Locator studentList = page.locator("#student-list-fragment");
        for (String student : new String[] {"E2E-Bleibt-" + unique, leaver}) {
            page.locator("#studentName").fill(student);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
            assertThat(studentList).containsText(student);
        }
        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();

        // Grade table with one Leistung, and a grade for the student who will leave.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Mit Schriftlich")).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("+ Leistung in Schriftlich")).click();
        page.locator("#assessmentName").fill("KA1");
        page.locator("#assessment-dialog").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Speichern")).click();
        Locator leaverCell = page.locator("tr").filter(new Locator.FilterOptions().setHasText(leaver)).locator("input.grade-input");
        leaverCell.fill("4");
        leaverCell.evaluate("el => el.blur()");
        assertThat(page.locator(".assessment-average-final")).hasText("4");

        // Class page: mark as left (confirm prompt), the student moves to "Ausgetreten".
        page.locator(".breadcrumbs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(className)).click();
        page.onceDialog(Dialog::accept);
        // "Ausgetreten" is a rare action, so it sits in the row's edit row behind "Ändern".
        studentList.locator("tr").filter(new Locator.FilterOptions().setHasText(leaver))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Ändern")).click();
        studentList.locator("tr.edit-row.editing")
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Ausgetreten")).click();
        assertThat(page.locator(".left-student-list")).containsText(leaver);

        // Grade table: the grade is still there, read-only and outside the column average.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        Locator leftSection = page.locator("tbody.left-students");
        assertThat(leftSection).containsText(leaver);
        assertThat(leftSection).containsText("4");
        assertThat(leftSection.locator("input")).hasCount(0);
        assertThat(page.locator(".assessment-average-final")).hasText("–");

        // Taking the student back makes the cell editable again.
        page.locator(".breadcrumbs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(className)).click();
        page.locator(".left-student-list").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Ändern")).click();
        page.locator(".left-student-list tr.edit-row.editing")
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Wieder aufnehmen")).click();
        assertThat(page.locator(".left-student-list")).hasCount(0);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        assertThat(page.locator("tbody.left-students")).hasCount(0);
        assertThat(page.locator("tr").filter(new Locator.FilterOptions().setHasText(leaver)).locator("input.grade-input"))
                .hasValue("4");
    }
}
