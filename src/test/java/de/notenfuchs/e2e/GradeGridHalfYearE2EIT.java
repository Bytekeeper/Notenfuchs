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
 * Browser-driven end-to-end coverage for the Halbjahr split view: setting a cutoff date on the
 * class detail page (see {@code fragments/halfYearCutoff.html}), and the resulting "1./2.
 * Halbjahr" column blocks + their own average columns in the grade grid
 * ({@code GridPage/gridHalfYear.html}). Complements {@code de.notenfuchs.web.GradeGridHalfYearIT}
 * (HTTP/JSON-level average assertions) with the actual UI flow a teacher would use, and confirms
 * keyboard navigation/autosave still works across the H1/H2 column blocks. A Failsafe IT
 * (./mvnw verify) for the same reasons as {@link GradeGridE2EIT}.
 */
@QuarkusTest
@WithPlaywright
class GradeGridHalfYearE2EIT {

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
    void settingCutoffSplitsGridIntoHalvesWithCorrectLiveAverages() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-HJ-Klasse-" + unique;
        String studentName = "E2E-HJ-Schueler-" + unique;
        String subjectName = "E2E-HJ-Fach-" + unique;
        String categoryName = "E2E-HJ-Kategorie-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        // Set the Halbjahr cutoff via the rename-wrap form on the class detail page.
        Locator cutoffWrap = page.locator("#half-year-cutoff-fragment .rename-wrap");
        cutoffWrap.locator(".rename-toggle").click();
        cutoffWrap.locator("input[name='halfYearCutoff']").fill("2026-01-31");
        cutoffWrap.locator(".rename-save").click();
        assertThat(page.locator("#half-year-cutoff-fragment .rename-display")).hasText("2026-01-31");

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
        Locator assessmentRows = categoryCard.locator("table.entity-list tbody tr:not(.empty-row)");

        // Dated before the cutoff -> lands in 1. Halbjahr.
        categoryCard.locator("form.inline-form input[name='name']").fill("H1-Klausur");
        categoryCard.locator("form.inline-form input[name='date']").fill("2026-01-15");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        // Wait for the htmx swap to land before touching the (freshly re-rendered) add-form
        // again - otherwise the second fill races the first request's in-flight response.
        assertThat(assessmentRows).hasCount(1);

        // Dated after the cutoff -> lands in 2. Halbjahr.
        categoryCard.locator("form.inline-form input[name='name']").fill("H2-Klausur");
        categoryCard.locator("form.inline-form input[name='date']").fill("2026-02-15");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        assertThat(assessmentRows).hasCount(2);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        // The three region blocks render; no "Ohne Datum" block since both Leistungen are dated.
        assertThat(page.locator("th.region-header").filter(new Locator.FilterOptions().setHasText("1. Halbjahr"))).isVisible();
        assertThat(page.locator("th.region-header").filter(new Locator.FilterOptions().setHasText("2. Halbjahr"))).isVisible();
        assertThat(page.getByRole(AriaRole.COLUMNHEADER, new Page.GetByRoleOptions().setName("Jahr").setExact(true))).isVisible();
        assertThat(page.locator("th.region-header-undated")).hasCount(0);

        Locator h1Cell = page.locator("input.grade-input[data-row='0'][data-col='0']");
        Locator h2Cell = page.locator("input.grade-input[data-row='0'][data-col='1']");
        Locator h1Final = page.locator(".average-final[data-scope='h1']");
        Locator h2Final = page.locator(".average-final[data-scope='h2']");
        Locator jahrFinal = page.locator(".average-final[data-scope='jahr']");

        assertThat(h1Final).hasText("–");
        assertThat(h2Final).hasText("–");
        assertThat(jahrFinal).hasText("–");

        // Grading only the H1 Leistung: H1 and Jahr both become 2 (the only grade so far), H2 stays empty.
        h1Cell.fill("2");
        h1Cell.evaluate("el => el.blur()");
        assertThat(h1Final).hasText("2");
        assertThat(jahrFinal).hasText("2");
        assertThat(h2Final).hasText("–");

        // Keyboard nav must still work seamlessly across the H1 -> H2 block boundary.
        h1Cell.click();
        page.keyboard().press("Tab");
        assertThat(h2Cell).isFocused();

        // Grading the H2 Leistung with 4: H2 becomes 4, Jahr averages both (2+4)/2=3,
        // H1 must stay exactly 2 - the H2 edit must not leak into it.
        h2Cell.fill("4");
        h2Cell.evaluate("el => el.blur()");
        assertThat(h2Final).hasText("4");
        assertThat(jahrFinal).hasText("3");
        assertThat(h1Final).hasText("2");

        // Persists correctly across reload, laid out into the same blocks.
        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='0']")).hasValue("2");
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='1']")).hasValue("4");
        assertThat(page.locator(".average-final[data-scope='h1']")).hasText("2");
        assertThat(page.locator(".average-final[data-scope='h2']")).hasText("4");
        assertThat(page.locator(".average-final[data-scope='jahr']")).hasText("3");
    }

    /**
     * Covers {@code fragments/halfYearGradeDisplay.html} + its notenfuchs.js live-disable
     * behavior: the tendency % input only makes sense for "Ganze Noten" and must disable itself
     * the moment "Halbe Noten" is picked, even before saving. Then checks both display modes
     * actually change what the H1 average cell renders - a half-grade for HALF, a whole grade
     * decorated with a tendency suffix for WHOLE+threshold - complementing the JSON-level
     * assertions in {@code de.notenfuchs.web.GradeGridHalfYearIT}.
     */
    @Test
    void halfYearGradeDisplaySetting_changesHowTheH1AverageCellRenders() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-HJ-Anzeige-Klasse-" + unique;
        String studentName = "E2E-HJ-Anzeige-Schueler-" + unique;
        String subjectName = "E2E-HJ-Anzeige-Fach-" + unique;
        String categoryName = "E2E-HJ-Anzeige-Kategorie-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        Locator cutoffWrap = page.locator("#half-year-cutoff-fragment .rename-wrap");
        cutoffWrap.locator(".rename-toggle").click();
        cutoffWrap.locator("input[name='halfYearCutoff']").fill("2026-01-31");
        cutoffWrap.locator(".rename-save").click();
        assertThat(page.locator("#half-year-cutoff-fragment .rename-display")).hasText("2026-01-31");

        // Switch to "Halbe Noten" - live-disabling the tendency input along the way, before it's
        // even saved (see HalfYearGradeDisplayService's javadoc for why the combination must be
        // structurally impossible).
        Locator displayWrap = page.locator("#half-year-grade-display-fragment .rename-wrap");
        displayWrap.locator(".rename-toggle").click();
        Locator modeSelect = displayWrap.locator("select[name='halfYearGradeDisplay']");
        Locator tendencyInput = displayWrap.locator("input[name='tendencyThresholdPercent']");
        assertThat(tendencyInput).isEnabled();
        modeSelect.selectOption("HALF");
        assertThat(tendencyInput).isDisabled();
        displayWrap.locator(".rename-save").click();
        assertThat(page.locator("#half-year-grade-display-fragment .rename-display")).hasText("Halbe Noten");

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
        categoryCard.locator("form.inline-form input[name='name']").fill("H1-Klausur");
        categoryCard.locator("form.inline-form input[name='date']").fill("2026-01-15");
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        assertThat(categoryCard.locator("table.entity-list tbody tr:not(.empty-row)")).hasCount(1);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        Locator h1Cell = page.locator("input.grade-input[data-row='0'][data-col='0']");
        h1Cell.fill("2.6");
        h1Cell.evaluate("el => el.blur()");
        // HALF mode: 2.6 rounds to the nearest half-grade (2.5) - never a whole grade, never a
        // tendency suffix.
        assertThat(page.locator(".average-final[data-scope='h1']")).hasText("2.5");

        // Switch back to whole grades with a +/-10% tendency band, then re-render the grid fresh.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        Locator displayWrap2 = page.locator("#half-year-grade-display-fragment .rename-wrap");
        displayWrap2.locator(".rename-toggle").click();
        displayWrap2.locator("select[name='halfYearGradeDisplay']").selectOption("WHOLE");
        Locator tendencyInput2 = displayWrap2.locator("input[name='tendencyThresholdPercent']");
        assertThat(tendencyInput2).isEnabled();
        tendencyInput2.fill("10");
        // The live example spells out what the bare number means (percent of a whole grade
        // step), recomputed on every keystroke - see notenfuchs.js's updateTendencyExample.
        assertThat(displayWrap2.locator(".tendency-example")).hasText("Beispiel bei Note 3: 2,90–3,10 ohne Tendenz, sonst 3+ / 3-");
        displayWrap2.locator(".rename-save").click();
        assertThat(page.locator("#half-year-grade-display-fragment .rename-display")).hasText("Ganze Noten (± 10% Tendenz)");

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();

        // 2.6 rounds to the whole grade 3 (COMMERCIAL) and sits >10% below it on the DE 1-6
        // scale (lower is better) - leaning toward the better neighbor 2 -> "3+".
        assertThat(page.locator(".average-final[data-scope='h1']")).hasText("3+");
    }
}
