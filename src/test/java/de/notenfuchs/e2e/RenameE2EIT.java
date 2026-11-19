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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Browser-driven end-to-end coverage for renaming named entities (Klasse, Schüler, Fach,
 * Kategorie, Leistung) through the server-rendered Qute/HTMX UI.
 *
 * <p>Rename is an inline click-to-edit control: a pencil button reveals a small form (name
 * input + save/cancel) in place of the display text, toggled purely client-side via a CSS
 * class (see the "Inline rename" block in {@code notenfuchs.js}) - no browser dialog. Saving
 * submits a normal htmx PATCH request, same as any other form in this app.
 *
 * <p>Runs as a Failsafe IT (./mvnw verify) for the same reasons as {@link GradeGridE2EIT}:
 * needs a real running app, a browser (via quarkus-playwright's Dev Services container) and
 * Postgres. Each test creates its own uniquely-named class/subject/student/category so tests
 * stay independent without needing a DB reset between tests.
 */
@QuarkusTest
@WithPlaywright
class RenameE2EIT {

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
    void classCanBeRenamedInItsSettings() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-" + unique;
        String renamedName = className + "-neu";

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Einstellungen")).click();

        page.locator("#className").fill(renamedName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Speichern").setExact(true)).first().click();

        assertThat(page.locator("h1")).containsText(renamedName);
        page.navigate(baseUrl() + "classes");
        assertThat(page.locator("a.class-card").filter(new Locator.FilterOptions().setHasText(renamedName))).hasCount(1);
    }

    @Test
    void discardingAnEditRowKeepsTheOldValues() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Cancel-Klasse-" + unique;
        String studentName = "Rename-Cancel-Schueler-" + unique;

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        AddPanels.open(page, "+ Schüler hinzufügen");
        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();

        Locator row = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(studentName));
        Locator editRow = openEditRow(row);
        editRow.locator("input[name='name']").fill(studentName + "-should-not-be-saved");
        editRow.locator(".rename-cancel").click();
        assertThat(editRow).not().hasClass(java.util.regex.Pattern.compile(".*editing.*"));

        // Reopening shows the stored name again, not the discarded input.
        openEditRow(row);
        assertThat(editRow.locator("input[name='name']")).hasValue(studentName);
    }

    @Test
    void studentCanBeRenamedFromTheClassDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Schueler-" + unique;
        String studentName = "Rename-Schueler-" + unique;
        String renamedName = studentName + "-neu";

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();

        Locator row = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(studentName));
        renameInEditRow(row, renamedName);

        Locator renamedRow = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(renamedName));
        assertThat(renamedRow).isVisible();

        page.reload();
        assertThat(page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();
    }

    @Test
    void subjectCanBeRenamedFromTheClassDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Fach-" + unique;
        String subjectName = "Rename-Fach-" + unique;
        String renamedName = subjectName + "-neu";

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);

        Locator item = page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName));
        renameInEditRow(item, renamedName);

        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(renamedName).setExact(true))).isVisible();

        page.reload();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(renamedName).setExact(true))).isVisible();
    }

    @Test
    void subjectRoundingModeCanBeChangedFromTheClassDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Rundung-" + unique;
        String subjectName = "Rename-Fach-Rundung-" + unique;

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        // createSubject always picks COMMERCIAL, so switching to IN_FAVOR_OF_STUDENT below is a real change.
        createSubject(subjectName);

        Locator item = page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName));
        Locator wrap = openEditRow(item);
        wrap.locator(".rename-form select[name='roundingMode']").selectOption("IN_FAVOR_OF_STUDENT");
        wrap.locator(".rename-save").click();

        java.util.regex.Pattern favorOfStudent = java.util.regex.Pattern.compile(".*Zugunsten des Schülers.*");
        Locator itemAfterSave = page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName));
        assertThat(itemAfterSave.locator(".row-meta")).hasText(favorOfStudent);

        page.reload();
        Locator itemAfterReload = page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName));
        assertThat(itemAfterReload.locator(".row-meta")).hasText(favorOfStudent);
    }

    @Test
    void categoryCanBeRenamedFromTheSubjectDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Kat-" + unique;
        String subjectName = "Rename-Fach-Kat-" + unique;
        String categoryName = "Rename-Kategorie-" + unique;
        String renamedName = categoryName + "-neu";

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();

        AddPanels.open(page, "+ Kategorie anlegen");

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.locator("form:has(#categoryName) button[type='submit']").click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        // The category's own rename-wrap is the card header, rendered before any
        // per-assessment rename-wraps in the table below it.
        renameInline(categoryCard.locator(".rename-wrap").first(), renamedName);

        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();

        page.reload();
        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();
    }

    @Test
    void assessmentIsEditedInTheDialogFromTheSubjectDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Leistung-" + unique;
        String subjectName = "Rename-Fach-Leistung-" + unique;
        String categoryName = "Rename-Kategorie-Leistung-" + unique;
        String assessmentName = "Rename-Leistung-" + unique;
        String renamedName = assessmentName + "-neu";

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();
        String setupUrl = page.url();

        AddPanels.open(page, "+ Kategorie anlegen");
        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.locator("form:has(#categoryName) button[type='submit']").click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        Leistungen.add(page, categoryCard, assessmentName, "2026-01-15", false);

        // "Ändern" opens the grade grid's Leistung dialog, filled in.
        Locator row = page.locator("tr.assessment-row").filter(new Locator.FilterOptions().setHasText(assessmentName));
        row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Ändern")).click();
        Locator dialog = page.locator("#assessment-dialog");
        assertThat(dialog.locator("#assessmentName")).hasValue(assessmentName);
        assertThat(dialog.locator("#assessmentDate")).hasValue("2026-01-15");
        dialog.locator("#assessmentName").fill(renamedName);
        dialog.locator("#assessmentFactor").fill("2");
        dialog.locator("#assessmentDate").fill("");
        page.waitForNavigation(() -> dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Speichern")).click());

        // Saving comes back to this page, not the grid.
        assertEquals(setupUrl, page.url());
        Locator renamedRow = page.locator("tr.assessment-row").filter(new Locator.FilterOptions().setHasText(renamedName));
        assertThat(renamedRow.locator(".row-meta")).hasText(java.util.regex.Pattern.compile("^Faktor 2(\\.0+)?$"));
        assertThat(renamedRow.locator("td").nth(1)).hasText("");
    }

    @Test
    void categoryWeightPercentCanBeChangedFromTheSubjectDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Gewichtung-" + unique;
        String subjectName = "Rename-Fach-Gewichtung-" + unique;
        String categoryName = "Rename-Kategorie-Gewichtung-" + unique;

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();

        AddPanels.open(page, "+ Kategorie anlegen");

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("50");
        page.locator("form:has(#categoryName) button[type='submit']").click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        Locator wrap = categoryCard.locator(".rename-wrap").first();
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form input[name='weightPercent']").fill("75");
        wrap.locator(".rename-save").click();

        // The weightPercent column is NUMERIC(5,2), so a reload may render "75" as "75.00" -
        // match loosely on the DB-normalized scale rather than the exact typed value.
        java.util.regex.Pattern seventyFivePercent = java.util.regex.Pattern.compile("^\\(75(\\.0+)?%\\)$");

        Locator categoryCardAfterSave = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(categoryCardAfterSave.locator(".hint").first()).hasText(seventyFivePercent);

        page.reload();
        Locator categoryCardAfterReload = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(categoryCardAfterReload.locator(".hint").first()).hasText(seventyFivePercent);
    }

    /**
     * Drives the inline click-to-edit control scoped to a single {@code .rename-wrap}:
     * click the pencil, fill the revealed input, click save.
     */
    private void renameInEditRow(Locator displayRow, String newName) {
        Locator editRow = openEditRow(displayRow);
        editRow.locator(".rename-form input[name='name']").fill(newName);
        editRow.locator(".rename-save").click();
    }

    private void renameInline(Locator wrap, String newName) {
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form input[name='name']").fill(newName);
        wrap.locator(".rename-save").click();
    }

    /** A list row's "Ändern" opens a separate edit row below it, wired via data-rename-target. */
    private Locator openEditRow(Locator displayRow) {
        Locator toggle = displayRow.locator(".rename-toggle");
        String targetId = toggle.getAttribute("data-rename-target");
        toggle.click();
        return page.locator("#" + targetId);
    }

    private void createClass(String className) {
        page.navigate(baseUrl());
        AddPanels.open(page, "+ Klasse anlegen");
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
    }

    private void createSubject(String subjectName) {
        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
    }
}
