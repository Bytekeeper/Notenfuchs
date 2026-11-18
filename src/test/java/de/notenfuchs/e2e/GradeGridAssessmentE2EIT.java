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
 * Adding, editing and deleting a Leistung directly from the grade table (the dialog in
 * {@code GridPage/assessmentDialog.html}), without going through "Leistungen & Gewichtung".
 */
@QuarkusTest
@WithPlaywright
class GradeGridAssessmentE2EIT {

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
    void addEditAndDeleteALeistungFromTheGradeTable() {
        String unique = Long.toString(System.nanoTime());
        String categoryName = "E2E-Kategorie-" + unique;
        setUpSubjectWithEmptyCategory(unique, categoryName);
        Locator dialog = page.locator("#assessment-dialog");

        // Empty state: no columns yet, but one "+ Leistung" button per category.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("+ Leistung in " + categoryName)).click();
        page.locator("#assessmentName").fill("KA1");
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Speichern")).click();

        // Back on the grid with the cursor already in the new column.
        Locator ka1Cell = page.locator("input.grade-input[data-row='0'][data-col='0']");
        assertThat(ka1Cell).isFocused();
        page.keyboard().type("2");
        ka1Cell.evaluate("el => el.blur()");
        assertThat(page.locator(".average-final")).hasText("2");

        // Second Leistung via the "+" in the category header, counting double.
        page.locator("th.category-header .column-add").click();
        page.locator("#assessmentName").fill("KA2");
        page.locator("#assessmentFactor").fill("2");
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Speichern")).click();
        Locator ka2Cell = page.locator("input.grade-input[data-row='0'][data-col='1']");
        assertThat(ka2Cell).isFocused();
        page.keyboard().type("5");
        ka2Cell.evaluate("el => el.blur()");
        assertThat(page.locator(".average-raw")).hasText("4");

        // Edit KA2 from its column header: rename and drop the factor back to 1.
        page.locator(".column-edit").filter(new Locator.FilterOptions().setHasText("KA2")).click();
        assertThat(page.locator("#assessmentName")).hasValue("KA2");
        page.locator("#assessmentName").fill("KA2-neu");
        page.locator("#assessmentFactor").fill("1");
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Speichern")).click();
        assertThat(page.locator(".column-edit").nth(1)).hasText("KA2-neu");
        assertThat(page.locator(".average-raw")).hasText("3.50");

        // Delete it again; the confirm prompt must be accepted.
        page.locator(".column-edit").filter(new Locator.FilterOptions().setHasText("KA2-neu")).click();
        page.onceDialog(Dialog::accept);
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung löschen")).click();
        assertThat(page.locator(".column-edit")).hasCount(1);
        assertThat(page.locator(".average-final")).hasText("2");
    }

    private void setUpSubjectWithEmptyCategory(String unique, String categoryName) {
        String className = "E2E-Klasse-" + unique;
        String subjectName = "E2E-Fach-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#studentName").fill("E2E-Schueler-" + unique);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName))).hasCount(1);

        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Notenerfassung")).click();
    }
}
