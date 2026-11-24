package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
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
import java.util.ArrayList;
import java.util.List;
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
    void ctrlZTakesBackSavedChangesOneByOne() {
        setUpSubjectWithGrid(Long.toString(System.nanoTime()));
        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        Locator cell1 = page.locator("input.grade-input[data-row='0'][data-col='1']");
        Locator finalGrade = page.locator(".average-final");

        cell0.fill("2");
        cell0.press("Tab");
        assertThat(finalGrade).hasText("2");
        cell0.fill("4");
        cell0.evaluate("el => el.blur()");
        assertThat(finalGrade).hasText("4");

        // Focus in another, untouched cell: Ctrl+Z belongs to the grid, not to the field.
        cell1.focus();
        page.keyboard().press("Control+z");
        assertThat(cell0).hasValue("2");
        assertThat(finalGrade).hasText("2");
        assertThat(page.locator(".undo-note")).containsText("wieder „2“");

        page.keyboard().press("Control+z");
        assertThat(cell0).hasValue("");
        assertThat(finalGrade).hasText("–");
        assertThat(page.locator(".undo-note")).containsText("wieder leer");

        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='0']")).hasValue("");
    }

    @Test
    void aGradeTheServerWritesDifferentlyIsNotSavedAgainOnTheNextBlur() {
        setUpSubjectWithGrid(Long.toString(System.nanoTime()));
        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        List<String> saves = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().endsWith("/grid/cell")) {
                saves.add(request.postData());
            }
        });

        cell0.fill("2,3");
        cell0.evaluate("el => el.blur()");
        assertThat(cell0).hasValue("2-");
        assertThat(page.locator(".average-raw")).hasText("2,3");
        cell0.focus();
        cell0.evaluate("el => el.blur()");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertEquals(1, saves.size(), saves.toString());
    }

    @Test
    void clearingACellThatWasAlreadyFilledOnPageLoadDeletesTheGrade() {
        // Regression: change detection started from "" instead of the rendered value, so clearing
        // a grade that came with the page was never sent and the grade silently stayed.
        setUpSubjectWithGrid(Long.toString(System.nanoTime()));
        Locator cell0 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        cell0.fill("2");
        cell0.evaluate("el => el.blur()");
        assertThat(page.locator(".average-final")).hasText("2");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.reload();
        Locator reloaded = page.locator("input.grade-input[data-row='0'][data-col='0']");
        assertThat(reloaded).hasValue("2");
        reloaded.fill("");
        reloaded.evaluate("el => el.blur()");
        assertThat(page.locator(".average-final")).hasText("–");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='0']")).hasValue("");
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
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();
        AddPanels.open(page, "+ Kategorie anlegen");
        page.locator("#categoryName").fill("E2E-LeereKategorie-" + unique);
        page.locator("#weightPercent").fill("0");
        page.locator("form:has(#categoryName) button[type='submit']").click();

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
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();
        AddPanels.open(page, "+ Kategorie anlegen");
        page.locator("#categoryName").fill("E2E-LeereKategorie-" + unique);
        page.locator("#weightPercent").fill("0");
        page.locator("form:has(#categoryName) button[type='submit']").click();
        // Wait for the htmx swap to land before submitting again - otherwise the second fill()
        // races the first request's still-in-flight outerHTML swap (see CLAUDE.md's note on
        // GradeGridHalfYearE2EIT for the same gotcha).
        Locator secondCategoryCard = page.locator(".card")
                .filter(new Locator.FilterOptions().setHasText("E2E-LeereKategorie-" + unique));
        assertThat(secondCategoryCard).isVisible();

        AddPanels.open(page, "+ Kategorie anlegen");

        page.locator("#categoryName").fill("E2E-KategorieNachLuecke-" + unique);
        page.locator("#weightPercent").fill("0");
        page.locator("form:has(#categoryName) button[type='submit']").click();

        Locator thirdCategoryCard = page.locator(".card")
                .filter(new Locator.FilterOptions().setHasText("E2E-KategorieNachLuecke-" + unique));
        Leistungen.add(page, thirdCategoryCard, "LeistungNachLuecke", null, false);
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
        // Let both autosave requests finish; reloading earlier can abort one of them.
        page.waitForLoadState(LoadState.NETWORKIDLE);

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

        // Bonus points above the maximum of 60 still give the best grade.
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
    void theFachKeyFillsTheStepsUntilTheTeacherEditsOne() {
        String unique = Long.toString(System.nanoTime());
        String assessmentName = "E2E-Klausur-Schluessel-" + unique;
        setUpSubjectWithPointsBasedGrid(unique, assessmentName);
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();

        // The 1 from 90 % instead of 87,5 %, read sliding; the dialog on the same page must use the saved key.
        Locator keyForm = page.locator("#points-key-form");
        keyForm.locator("input[name='percent']").first().fill("90");
        keyForm.locator("input[name='sliding']").check();
        keyForm.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Speichern")).click();
        assertThat(page.locator("#points-key-form .saved-note")).isVisible();

        page.locator(".card").filter(new Locator.FilterOptions().setHasText(assessmentName))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("+ Leistung anlegen")).click();
        Locator dialog = page.locator("#assessment-dialog");
        dialog.locator("#assessmentName").fill("Test-" + unique);
        dialog.locator("#assessmentPointsBased").check();
        dialog.locator("#assessmentMaxPoints").fill("40");
        Locator stepPoints = dialog.locator("input[name='bandPoints']");
        assertThat(stepPoints).hasCount(5);
        assertThat(stepPoints.first()).hasValue("36");
        assertThat(dialog.locator("input[name='bandGrades']").first()).hasValue("1");
        assertThat(dialog.locator("#assessmentPointsSliding")).isChecked();

        // An edited step stays when the maximum changes, until the key is applied again.
        stepPoints.nth(1).fill("31");
        dialog.locator("#assessmentMaxPoints").fill("50");
        assertThat(stepPoints.nth(1)).hasValue("31");
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Schlüssel des Fachs anwenden")).click();
        assertThat(stepPoints.first()).hasValue("45");
        assertThat(stepPoints.nth(1)).hasValue("37.5");
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
        AddPanels.open(page, "+ Klasse anlegen");
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
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();

        AddPanels.open(page, "+ Kategorie anlegen");

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.locator("form:has(#categoryName) button[type='submit']").click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        Leistungen.add(page, categoryCard, assessmentName, null, true);
        assertThat(categoryCard.locator("tr.assessment-row")).hasCount(1);

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
        AddPanels.open(page, "+ Klasse anlegen");
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
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();

        // Subject detail page: one category, weighted 100%, with two assessments.
        AddPanels.open(page, "+ Kategorie anlegen");
        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.locator("form:has(#categoryName) button[type='submit']").click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        Locator assessmentRows = categoryCard.locator("table.entity-list tbody tr.assessment-row");

        Leistungen.add(page, categoryCard, "Test1", null, false);
        assertThat(assessmentRows).hasCount(1);

        Leistungen.add(page, categoryCard, "Test2", null, false);
        assertThat(assessmentRows).hasCount(2);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
    }
}
