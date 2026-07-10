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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Regression coverage for deleting an entity that still has dependents (a Leistung with
 * entered Noten, a Kategorie with Leistungen, ...). The delete buttons' hx-confirm text
 * ("Das löscht auch alle Noten dieser Leistung.") promises a cascade, so every child FK in the
 * schema carries ON DELETE CASCADE; these tests exercise that cascade through the real UI
 * against a real Postgres (Testcontainers), not just at the SQL level.
 */
@QuarkusTest
@WithPlaywright
class DeleteCascadeE2EIT {

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
    void assessmentWithAGradeCanStillBeDeleted() {
        String unique = Long.toString(System.nanoTime());
        String className = "Cascade-Klasse-Leistung-" + unique;
        String studentName = "Cascade-Schueler-Leistung-" + unique;
        String subjectName = "Cascade-Fach-Leistung-" + unique;
        String categoryName = "Cascade-Kategorie-Leistung-" + unique;
        String assessmentName = "Cascade-Leistung-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();

        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        // Enter a grade for the assessment via the real grid, so the Assessment now has a
        // dependent Grade row - this is what previously made the delete below fail.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        cell0.fill("2");
        cell0.evaluate("el => el.blur()");

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        Locator row = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName))
                .locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));

        page.onDialog(dialog -> dialog.accept());
        row.locator("form button.delete-btn").click();

        assertThat(page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName)))
                .hasCount(0);

        page.reload();
        assertThat(page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName)))
                .hasCount(0);
    }

    @Test
    void categoryWithAssessmentsAndGradesCanStillBeDeleted() {
        String unique = Long.toString(System.nanoTime());
        String className = "Cascade-Klasse-Kat-" + unique;
        String studentName = "Cascade-Schueler-Kat-" + unique;
        String subjectName = "Cascade-Fach-Kat-" + unique;
        String categoryName = "Cascade-Kategorie-" + unique;
        String assessmentName = "Cascade-Leistung-Kat-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();

        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        cell0.fill("2");
        cell0.evaluate("el => el.blur()");

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        Locator card = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));

        page.onDialog(dialog -> dialog.accept());
        card.locator("> div form button.delete-btn").filter(new Locator.FilterOptions().setHasText("Kategorie löschen")).click();

        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName))).hasCount(0);

        page.reload();
        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName))).hasCount(0);
    }
}
