package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void tabNavigationSkipsAnEmptyCategoryColumnAndStillSavesTheEditedCell() {
        // Regression test for a real bug report: an empty (not-yet-populated) category between
        // two graded ones reserves a placeholder column with no <input> (see
        // secondCategoryWithNoAssessmentsYetDoesNotShiftAverageColumn above) - but that
        // placeholder used to also be excluded from GradeGridResource#loadGridData's maxCol
        // (computed from the total assessment count, ignoring placeholder columns), pushing
        // every column after it beyond the addressable data-col range entirely. Combined with
        // notenfuchs.js not skipping past a gap it DID reach, pressing Tab out of the cell right
        // before the gap silently failed to move focus at all - which also meant that cell's own
        // edit never blurred and never autosaved.
        String unique = Long.toString(System.nanoTime());
        setUpSubjectWithGrid(unique);

        // setUpSubjectWithGrid leaves one category ("E2E-Kategorie-<unique>") with two
        // assessments (col 0/1, both graded via cell0/cell1 in other tests). Add an empty
        // category, then a third with its own assessment - the empty one must not make
        // "LeistungNachLuecke" unreachable.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("E2E-Fach-" + unique)).click();
        page.locator("#categoryName").fill("E2E-LeereKategorie-" + unique);
        page.locator("#weightPercent").fill("0");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        // Wait for the htmx swap to land before submitting again - otherwise the second fill()
        // races the first request's still-in-flight outerHTML swap (see CLAUDE.md's note on
        // GradeGridHalfYearE2EIT for the same gotcha).
        Locator secondCategoryCard = page.locator(".card")
                .filter(new Locator.FilterOptions().setHasText("E2E-LeereKategorie-" + unique));
        assertThat(secondCategoryCard).isVisible();

        page.locator("#categoryName").fill("E2E-KategorieNachLuecke-" + unique);
        page.locator("#weightPercent").fill("0");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator thirdCategoryCard = page.locator(".card")
                .filter(new Locator.FilterOptions().setHasText("E2E-KategorieNachLuecke-" + unique));
        thirdCategoryCard.locator("form.inline-form input[name='name']").fill("LeistungNachLuecke");
        thirdCategoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        assertThat(thirdCategoryCard.locator("table.entity-list tbody tr.assessment-row")).hasCount(1);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        // Columns: 0/1 = the first category's two assessments, 2 = the empty category's
        // placeholder (no input), 3 = LeistungNachLuecke - so maxCol must be 3, not 2.
        Locator cell1 = page.locator("input.grade-input[data-row='0'][data-col='1']");
        Locator cellAfterGap = page.locator("input.grade-input[data-row='0'][data-col='3']");
        assertThat(cellAfterGap).hasCount(1);
        assertThat(page.locator("table.grade-grid-root")).hasAttribute("data-max-col", "3");

        cell1.fill("2");
        page.keyboard().press("Tab");
        assertThat(cellAfterGap).isFocused();

        cellAfterGap.fill("4");
        cellAfterGap.evaluate("el => el.blur()");

        // Both cells' edits must have actually persisted - proof that tabbing off cell1
        // (skipping over the gap) really blurred and autosaved it, not just that focus moved.
        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='1']")).hasValue("2");
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='3']")).hasValue("4");
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

    @Test
    void exportButtonDownloadsXlsxWithEnteredGrades() throws IOException {
        String unique = Long.toString(System.nanoTime());
        String studentName = "E2E-Schueler-" + unique;
        setUpSubjectWithGrid(unique);

        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        cell0.fill("2");
        cell0.evaluate("el => el.blur()");
        assertThat(page.locator(".average-final")).hasText("2");

        Download download = page.waitForDownload(() ->
                page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Als Excel exportieren")).click());
        assertTrue(download.suggestedFilename().endsWith(".xlsx"));

        Path savedPath = Files.createTempFile("notenfuchs-export-" + unique, ".xlsx");
        try {
            download.saveAs(savedPath);

            try (Workbook workbook = WorkbookFactory.create(savedPath.toFile())) {
                Sheet sheet = workbook.getSheetAt(0);

                // setUpSubjectWithGrid creates one category with two assessments (Test1, Test2),
                // so columns are: 0=Schüler, 1=Test1, 2=Test2, 3=Ø, 4=Note.
                Row headerRow1 = sheet.getRow(0);
                assertEquals("Schüler", headerRow1.getCell(0).getStringCellValue());
                assertEquals("Ø", headerRow1.getCell(3).getStringCellValue());
                assertEquals("Note", headerRow1.getCell(4).getStringCellValue());

                Row studentRow = sheet.getRow(2);
                assertEquals(studentName, studentRow.getCell(0).getStringCellValue());
                assertEquals(2.0, studentRow.getCell(1).getNumericCellValue(), 0.001);
                assertEquals(2.0, studentRow.getCell(3).getNumericCellValue(), 0.001);
                assertEquals(2.0, studentRow.getCell(4).getNumericCellValue(), 0.001);
            }
        } finally {
            Files.deleteIfExists(savedPath);
        }
    }

    @Test
    void pointsBasedAssessmentAcceptsPointsAndShowsDerivedGrade() {
        String unique = Long.toString(System.nanoTime());
        String assessmentName = "E2E-Klausur-" + unique;
        setUpSubjectWithPointsBasedGrid(unique, assessmentName);

        Locator pointsCell = page.locator("input.points-input[data-row='0'][data-col='0']");
        Locator derivedGrade = page.locator(".derived-grade");
        Locator finalGrade = page.locator(".average-final");

        assertThat(derivedGrade).hasText("");
        assertThat(finalGrade).hasText("–");

        // The default Notenschlüssel (60 -> 1, 20 -> 6) resolves 65 points to grade 1
        // (meets the >=60 band).
        pointsCell.fill("65");
        pointsCell.evaluate("el => el.blur()");

        assertThat(pointsCell).hasValue("65");
        assertThat(derivedGrade).hasText("1");
        assertThat(finalGrade).hasText("1");
        assertThat(page.locator(".average-raw")).hasText("1");

        // Raw points (not the derived grade) must be what's persisted and redisplayed.
        page.reload();
        assertThat(page.locator("input.points-input[data-row='0'][data-col='0']")).hasValue("65");
        assertThat(page.locator(".derived-grade")).hasText("1");
        assertThat(page.locator(".average-final")).hasText("1");
    }

    @Test
    void editingNotenschluesselBandRecomputesDerivedGradeLive() {
        // Anti-freeze design principle: the grade is derived live from points + bands, never
        // frozen - so editing a band's grade value must change the already-entered student's
        // grade without touching the stored points at all.
        String unique = Long.toString(System.nanoTime());
        String assessmentName = "E2E-Klausur-Live-" + unique;
        setUpSubjectWithPointsBasedGrid(unique, assessmentName);

        Locator pointsCell = page.locator("input.points-input[data-row='0'][data-col='0']");
        pointsCell.fill("65");
        pointsCell.evaluate("el => el.blur()");
        assertThat(page.locator(".derived-grade")).hasText("1");
        assertThat(page.locator(".average-final")).hasText("1");

        // Go back to the subject page (third breadcrumb link: Klassen / Klasse / Fach) and
        // change the ">=60 points" band's grade from 1 to 2 - the default bands render
        // best-to-worst, so this is the first band row.
        page.locator(".breadcrumbs a").nth(2).click();

        Locator bandForm = page.locator(".band-form").nth(0);
        Locator gradeValueInput = bandForm.locator("input[name='gradeValue']");
        gradeValueInput.fill("2");
        gradeValueInput.evaluate("el => el.blur()");
        // No Speichern button - a band autosaves on blur like a grade-grid cell (see
        // notenfuchs.js). Wait for the save-confirmation flash before navigating away, since
        // the PATCH now fires in the background rather than a synchronous button click.
        assertThat(gradeValueInput).hasClass(java.util.regex.Pattern.compile(".*\\bstate-saved\\b.*"));

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        // Same stored points (65), no re-entry - the derived grade must already reflect the
        // updated band. Bands are now 60->2 / 20->6: since points-based Notenschlüssel bands
        // extrapolate rather than clamp (see PointsConversionService), 65 points continues the
        // line's slope 5 points past the 60 anchor instead of just staying at that band's grade
        // - (6-2)/(20-60) = -0.1 per point, so 2 + (-0.1 * 5) = 1.5. The subject average then
        // rounds that same 1.5 the normal COMMERCIAL way (half up) to the whole grade 2.
        assertThat(page.locator("input.points-input[data-row='0'][data-col='0']")).hasValue("65");
        assertThat(page.locator(".derived-grade")).hasText("1.5");
        assertThat(page.locator(".average-final")).hasText("2");
    }

    @Test
    void changingAssessmentRoundingModeRecomputesDerivedGradeLive() {
        // Same anti-freeze reasoning as editingNotenschluesselBandRecomputesDerivedGradeLive,
        // but for the per-Leistung rounding mode instead of a band value: 30 points against the
        // default bands (60 -> 1, 20 -> 6) interpolates to raw grade 4.75. The default rounding
        // mode ("zugunsten des Schuelers") floors toward the better grade -> 4.7; switching the
        // assessment to "kaufmaennisch" must recompute the same stored points to the standard
        // half-up rounding -> 4.8, without touching the points at all.
        String unique = Long.toString(System.nanoTime());
        String assessmentName = "E2E-Klausur-Rundung-" + unique;
        setUpSubjectWithPointsBasedGrid(unique, assessmentName);

        Locator pointsCell = page.locator("input.points-input[data-row='0'][data-col='0']");
        pointsCell.fill("30");
        pointsCell.evaluate("el => el.blur()");
        assertThat(page.locator(".derived-grade")).hasText("4.7");

        page.locator(".breadcrumbs a").nth(2).click();

        Locator row = page.locator("table.entity-list tbody tr")
                .filter(new Locator.FilterOptions().setHasText(assessmentName)).first();
        // The row's single "Ändern" (in its actions cell) opens a combined edit row below,
        // wired via data-rename-target rather than DOM nesting - see RenameE2EIT's
        // openAssessmentEditRow for the same pattern.
        Locator toggle = row.locator(".rename-toggle");
        String targetId = toggle.getAttribute("data-rename-target");
        toggle.click();
        Locator editRow = page.locator("#" + targetId);
        editRow.locator("select[name='roundingMode']").selectOption("COMMERCIAL");
        editRow.locator(".rename-save").click();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        // Same stored points (30), no re-entry - now rounds the standard "kaufmaennisch" way.
        assertThat(page.locator("input.points-input[data-row='0'][data-col='0']")).hasValue("30");
        assertThat(page.locator(".derived-grade")).hasText("4.8");
    }

    /**
     * Creates a class with one student and a subject with one 100%-weighted category holding
     * a single points-based assessment (default Notenschlüssel seeded), then navigates to that
     * subject's grade grid - leaving exactly one row (row 0) and one column (col 0) for the
     * caller to grade by points.
     */
    private void setUpSubjectWithPointsBasedGrid(String unique, String assessmentName) {
        String className = "E2E-Klasse-Punkte-" + unique;
        String studentName = "E2E-Schueler-Punkte-" + unique;
        String subjectName = "E2E-Fach-Punkte-" + unique;
        String categoryName = "E2E-Kategorie-Punkte-" + unique;

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
        categoryCard.locator("form.inline-form input[name='pointsBased']").check();
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
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
        // .assessment-row is the one visible <tr> per Leistung - each also has a sibling
        // .assessment-edit-row (its hidden combined-edit form), which a plain :not(.empty-row)
        // count would otherwise double-count.
        Locator assessmentRows = categoryCard.locator("table.entity-list tbody tr.assessment-row");

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
