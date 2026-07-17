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
    void classCanBeRenamedFromTheClassList() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-" + unique;
        String renamedName = className + "-neu";

        createClass(className);

        Locator wrap = page.locator(".class-card").filter(new Locator.FilterOptions().setHasText(className))
                .locator(".rename-wrap");
        renameInline(wrap, renamedName);

        assertThat(page.locator(".class-card .class-name").filter(new Locator.FilterOptions().setHasText(renamedName)))
                .hasText(renamedName);
        assertThat(page.locator(".class-card .class-name").getByText(className, new Locator.GetByTextOptions().setExact(true)))
                .hasCount(0);

        // Renamed value must be persisted, not just reflected in the swapped-in fragment.
        page.reload();
        assertThat(page.locator(".class-card .class-name").filter(new Locator.FilterOptions().setHasText(renamedName)))
                .hasText(renamedName);
    }

    @Test
    void cancellingTheInlineFormLeavesTheNameUnchanged() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Cancel-Klasse-" + unique;

        createClass(className);

        Locator wrap = page.locator(".class-card").filter(new Locator.FilterOptions().setHasText(className))
                .locator(".rename-wrap");
        wrap.locator(".rename-toggle").click();
        assertThat(wrap).hasClass(java.util.regex.Pattern.compile(".*editing.*"));

        wrap.locator(".rename-form input[name='name']").fill(className + "-should-not-be-saved");
        wrap.locator(".rename-cancel").click();

        assertThat(wrap).not().hasClass(java.util.regex.Pattern.compile(".*editing.*"));
        assertThat(page.locator(".class-card .class-name").filter(new Locator.FilterOptions().setHasText(className)))
                .hasText(className);
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
        renameInline(row.locator(".rename-wrap"), renamedName);

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
        renameInline(item.locator(".rename-wrap"), renamedName);

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
        Locator wrap = item.locator(".rename-wrap");
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form select[name='roundingMode']").selectOption("IN_FAVOR_OF_STUDENT");
        wrap.locator(".rename-save").click();

        java.util.regex.Pattern favorOfStudent = java.util.regex.Pattern.compile(".*Zugunsten des Schülers.*");
        Locator itemAfterSave = page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName));
        assertThat(itemAfterSave.locator(".subject-meta")).hasText(favorOfStudent);

        page.reload();
        Locator itemAfterReload = page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName));
        assertThat(itemAfterReload.locator(".subject-meta")).hasText(favorOfStudent);
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

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        // The category's own rename-wrap is the card header, rendered before any
        // per-assessment rename-wraps in the table below it.
        renameInline(categoryCard.locator(".rename-wrap").first(), renamedName);

        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();

        page.reload();
        assertThat(page.locator(".card").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();
    }

    @Test
    void assessmentCanBeRenamedFromTheSubjectDetailPage() {
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

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        Locator row = categoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        // First .rename-wrap is name+factor (Leistung column); the second is the
        // Datum column's own inline-edit control.
        renameInline(row.locator(".rename-wrap").first(), renamedName);

        Locator categoryCardAfterRename = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(categoryCardAfterRename.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();

        page.reload();
        Locator categoryCardAfterReload = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(categoryCardAfterReload.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(renamedName))).isVisible();
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

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("50");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

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

    @Test
    void assessmentFactorCanBeChangedFromTheSubjectDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Faktor-" + unique;
        String subjectName = "Rename-Fach-Faktor-" + unique;
        String categoryName = "Rename-Kategorie-Faktor-" + unique;
        String assessmentName = "Rename-Leistung-Faktor-" + unique;

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        Locator row = categoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        // First .rename-wrap is name+factor (Leistung column); the second is the
        // Datum column's own inline-edit control.
        Locator wrap = row.locator(".rename-wrap").first();
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form input[name='factor']").fill("2");
        wrap.locator(".rename-save").click();

        // The factor column is NUMERIC(5,2), so a reload may render "2" as "2.00" - match
        // loosely on the DB-normalized scale rather than the exact typed value.
        java.util.regex.Pattern factorTwo = java.util.regex.Pattern.compile("^\\(Faktor 2(\\.0+)?\\)$");

        Locator rowAfterSave = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        assertThat(rowAfterSave.locator(".hint")).hasText(factorTwo);

        page.reload();
        Locator rowAfterReload = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        assertThat(rowAfterReload.locator(".hint")).hasText(factorTwo);
    }

    @Test
    void assessmentDateCanBeChangedFromTheSubjectDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-Datum-" + unique;
        String subjectName = "Rename-Fach-Datum-" + unique;
        String categoryName = "Rename-Kategorie-Datum-" + unique;
        String assessmentName = "Rename-Leistung-Datum-" + unique;

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.locator("form.inline-form input[name='date']").fill("2026-01-15");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        Locator row = categoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        // Second .rename-wrap is the Datum column's own inline-edit control (the first is
        // name+factor in the Leistung column).
        Locator wrap = row.locator(".rename-wrap").nth(1);
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form input[name='date']").fill("2026-03-20");
        wrap.locator(".rename-save").click();

        Locator rowAfterSave = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        assertThat(rowAfterSave.locator(".rename-wrap").nth(1).locator(".rename-display")).hasText("2026-03-20");

        page.reload();
        Locator rowAfterReload = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        assertThat(rowAfterReload.locator(".rename-wrap").nth(1).locator(".rename-display")).hasText("2026-03-20");
    }

    @Test
    void assessmentDateCanBeClearedFromTheSubjectDetailPage() {
        String unique = Long.toString(System.nanoTime());
        String className = "Rename-Klasse-DatumLeeren-" + unique;
        String subjectName = "Rename-Fach-DatumLeeren-" + unique;
        String categoryName = "Rename-Kategorie-DatumLeeren-" + unique;
        String assessmentName = "Rename-Leistung-DatumLeeren-" + unique;

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        createSubject(subjectName);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();

        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.locator("form.inline-form input[name='date']").fill("2026-01-15");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();

        Locator row = categoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        // Second .rename-wrap is the Datum column's own inline-edit control (the first is
        // name+factor in the Leistung column).
        Locator wrap = row.locator(".rename-wrap").nth(1);
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form input[name='date']").fill("");
        wrap.locator(".rename-save").click();

        Locator rowAfterSave = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        assertThat(rowAfterSave.locator(".rename-wrap").nth(1).locator(".rename-display")).hasText("");

        page.reload();
        Locator rowAfterReload = page.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName));
        assertThat(rowAfterReload.locator(".rename-wrap").nth(1).locator(".rename-display")).hasText("");
    }

    /**
     * Drives the inline click-to-edit control scoped to a single {@code .rename-wrap}:
     * click the pencil, fill the revealed input, click save.
     */
    private void renameInline(Locator wrap, String newName) {
        wrap.locator(".rename-toggle").click();
        wrap.locator(".rename-form input[name='name']").fill(newName);
        wrap.locator(".rename-save").click();
    }

    private void createClass(String className) {
        page.navigate(baseUrl());
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
