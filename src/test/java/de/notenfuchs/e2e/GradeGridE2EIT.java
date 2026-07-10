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

/**
 * Browser-driven end-to-end test of the grade-entry grid - Notenfuchs's headline feature -
 * through the real server-rendered Qute/HTMX UI. Runs as a Failsafe IT (./mvnw verify), not
 * a Surefire unit test, since it needs a real running app, a browser (via quarkus-playwright's
 * Dev Services container) and Postgres (via Testcontainers Dev Services).
 *
 * <p>The browser runs in a container (see application.properties'
 * {@code %test.quarkus.playwright.devservices.*}), while this app runs in-process in the test
 * JVM, so navigation uses {@code host.testcontainers.internal} rather than {@code localhost} to
 * reach it - see {@link #baseUrl()}.
 *
 * <p>Each test creates its own uniquely-named class/student/subject/category so tests stay
 * independent of each other and of any pre-existing data without needing a DB reset between
 * tests (there's no reset endpoint, and the app only ever has one running instance per JVM).
 */
@QuarkusTest
@WithPlaywright
class GradeGridE2EIT {

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
    void enteringGradesUpdatesLiveAverageAndPersistsAcrossReload() {
        setUpSubjectWithGrid(Long.toString(System.nanoTime()));

        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        Locator cell1 = page.locator("input.grade-input[data-row='0'][data-col='1']");
        Locator finalGrade = page.locator(".average-final");
        Locator rawAverage = page.locator(".average-raw");

        assertThat(finalGrade).hasText("–");

        // Grading the first (of two, equally-factored) assessments with a 2 already gives a
        // full average, since the subject average is normalized over graded assessments only.
        cell0.fill("2");
        cell0.evaluate("el => el.blur()");
        assertThat(finalGrade).hasText("2");
        assertThat(rawAverage).hasText("2");

        // Tab from cell 0 should land on cell 1 - exercises the grid's own keyboard nav, not
        // just autosave.
        cell0.click();
        page.keyboard().press("Tab");
        assertThat(cell1).isFocused();

        cell1.fill("4");
        cell1.evaluate("el => el.blur()");
        assertThat(finalGrade).hasText("3");
        assertThat(rawAverage).hasText("3");

        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='0']")).hasValue("2");
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='1']")).hasValue("4");
        assertThat(page.locator(".average-final")).hasText("3");
    }

    @Test
    void secondCategoryWithNoAssessmentsYetDoesNotShiftAverageColumn() {
        // Regression test: a category created but not yet populated with any Leistung used to
        // render with colspan="0" (browser-coerced to 1) while contributing zero <td>s to the
        // body/footer rows, desyncing the column count so the average column rendered under the
        // empty category's header slot instead of "Ø / Note".
        String unique = Long.toString(System.nanoTime());
        setUpSubjectWithGrid(unique);

        // We're already on the grid page with one category ("E2E-Kategorie-<unique>", 2
        // assessments). Go back to the subject page and add a second, still-empty category.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("E2E-Fach-" + unique)).click();
        page.locator("#categoryName").fill("E2E-LeereKategorie-" + unique);
        page.locator("#weightPercent").fill("0");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        cell0.fill("2");
        cell0.evaluate("el => el.blur()");

        // The average cell must still be the LAST cell in the row, immediately after the last
        // grade-entry cell - not shifted into the empty category's placeholder column.
        Locator lastCellInRow = page.locator("table.grade-grid tbody tr").first().locator("> td").last();
        assertThat(lastCellInRow).hasClass(Pattern.compile(".*average-cell.*"));
        assertThat(page.locator(".average-final")).hasText("2");
    }

    @Test
    void outOfRangeGradeIsRejectedWithVisibleError() {
        setUpSubjectWithGrid(Long.toString(System.nanoTime()));

        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");

        // DE 1-6 scale: max is 6, so 9 is out of range.
        cell0.fill("9");
        cell0.evaluate("el => el.blur()");

        assertThat(cell0).hasClass(Pattern.compile(".*state-error.*"));
        assertThat(page.locator(".average-final")).hasText("–");

        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='0']")).hasValue("");
    }

    /**
     * Creates a class with one student and a subject with one 100%-weighted category holding
     * two assessments, then navigates to that subject's grade grid - leaving exactly one row
     * (row 0) and two columns (col 0/1) for the caller to grade.
     */
    private void setUpSubjectWithGrid(String unique) {
        String className = "E2E-Klasse-" + unique;
        String studentName = "E2E-Schueler-" + unique;
        String subjectName = "E2E-Fach-" + unique;
        String categoryName = "E2E-Kategorie-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        // Class detail page: add the student and the subject (two independent HTMX forms).
        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();

        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        // Subject detail page: one category, weighted 100%, with two assessments.
        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        Locator assessmentRows = categoryCard.locator("table.entity-list tbody tr:not(.empty-row)");

        // Scoped to the "add assessment" form specifically - the category card also has an
        // inline-rename form with its own input[name='name'] (see RenameE2EIT).
        categoryCard.locator("form.inline-form input[name='name']").fill("Test1");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        assertThat(assessmentRows).hasCount(1);

        categoryCard.locator("form.inline-form input[name='name']").fill("Test2");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        assertThat(assessmentRows).hasCount(2);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
    }
}
