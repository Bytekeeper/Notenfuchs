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
 * Browser-driven end-to-end coverage for the Verhaltensnoten grid ({@link
 * de.notenfuchs.web.BehaviorGridResource}): entering a behavior/conduct grade per (student, Fach)
 * through the real server-rendered UI, the live per-Fach column average and per-student row
 * average (including the "borderline" highlight near a x.5 rounding boundary), and keyboard
 * navigation. Complements {@code de.notenfuchs.web.BehaviorGridIT} (HTTP/JSON-level assertions).
 * A Failsafe IT (./mvnw verify) for the same reasons as {@link GradeGridE2EIT}.
 *
 * <p>Unlike the grade grid, no Kategorie/Leistung setup is needed - Verhaltensnoten are entered
 * directly per (student, Fach), so the fixture is just a class with two students and two Fächer.
 */
@QuarkusTest
@WithPlaywright
class BehaviorGridE2EIT {

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
    void enteringBehaviorGradesUpdatesLiveAveragesWithBorderlineHighlight() {
        String unique = Long.toString(System.nanoTime());
        String className = "VN-E2E-Klasse-" + unique;
        // "A-"/"B-" prefixes guarantee stable name-sort order for both students and Fächer.
        String studentA = "VN-E2E-A-Schueler-" + unique;
        String studentB = "VN-E2E-B-Schueler-" + unique;
        String subjectA = "VN-E2E-A-Fach-" + unique;
        String subjectB = "VN-E2E-B-Fach-" + unique;

        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        // Students render as plain text (no drill-down page), not links - see studentList.html.
        page.locator("#studentName").fill(studentA);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(page.locator("#student-list-fragment .rename-display").filter(new Locator.FilterOptions().setHasText(studentA)))
                .isVisible();

        page.locator("#studentName").fill(studentB);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(page.locator("#student-list-fragment .rename-display").filter(new Locator.FilterOptions().setHasText(studentB)))
                .isVisible();

        page.locator("#subjectName").fill(subjectA);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectA))).isVisible();

        page.locator("#subjectName").fill(subjectB);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.locator("#roundingMode").selectOption("COMMERCIAL");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectB))).isVisible();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Verhaltensnoten erfassen")).click();

        Locator row0 = page.locator("table.behavior-grid-root tbody tr").nth(0); // student A
        Locator row1 = page.locator("table.behavior-grid-root tbody tr").nth(1); // student B
        Locator row0Average = row0.locator("td.average-cell");
        Locator row1Average = row1.locator("td.average-cell");
        Locator fach1Footer = page.locator("table.behavior-grid-root tfoot td.average-cell").nth(0);

        Locator cellA_Fach1 = page.locator("input.grade-input[data-row='0'][data-col='0']");
        Locator cellA_Fach2 = page.locator("input.grade-input[data-row='0'][data-col='1']");
        Locator cellB_Fach1 = page.locator("input.grade-input[data-row='1'][data-col='0']");

        assertThat(row0Average).hasText("–");

        // Student A gets a 2 in FachA -> row average = 2, not borderline. Live JS updates use the
        // save endpoint's trailing-zero-stripped JSON value (like the grade grid's own cell-save
        // response) - only a fresh server render (see the reload assertions below) keeps the
        // fixed 2-decimal BigDecimal format ("2.00").
        cellA_Fach1.fill("2");
        cellA_Fach1.evaluate("el => el.blur()");
        assertThat(row0Average.locator(".behavior-average-raw")).hasText("2");
        assertThat(row0Average).not().hasClass(Pattern.compile(".*borderline.*"));
        assertThat(fach1Footer.locator(".behavior-subject-average-final")).hasText("2");

        // Keyboard nav: Tab from FachA's cell must land on FachB's cell, same row.
        cellA_Fach1.click();
        page.keyboard().press("Tab");
        assertThat(cellA_Fach2).isFocused();

        // Student A gets a 3 in FachB -> row average = (2+3)/2 = 2.5, right at the borderline.
        cellA_Fach2.fill("3");
        cellA_Fach2.evaluate("el => el.blur()");
        assertThat(row0Average.locator(".behavior-average-raw")).hasText("2.5");
        assertThat(row0Average).hasClass(Pattern.compile(".*borderline.*"));

        // Student B gets a 4 in FachA -> FachA's column average becomes (2+4)/2 = 3.
        // Student B's own row average is 4 (not borderline); student A's row must stay 2.5/borderline.
        cellB_Fach1.fill("4");
        cellB_Fach1.evaluate("el => el.blur()");
        assertThat(fach1Footer.locator(".behavior-subject-average-final")).hasText("3");
        assertThat(row1Average.locator(".behavior-average-raw")).hasText("4");
        assertThat(row1Average).not().hasClass(Pattern.compile(".*borderline.*"));
        assertThat(row0Average.locator(".behavior-average-raw")).hasText("2.5");
        assertThat(row0Average).hasClass(Pattern.compile(".*borderline.*"));

        // A fresh server render (Qute interpolating the BigDecimal directly, not through the
        // JSON endpoint's plain()) keeps the fixed 2-decimal format.
        page.reload();
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='0']")).hasValue("2");
        assertThat(page.locator("input.grade-input[data-row='0'][data-col='1']")).hasValue("3");
        assertThat(page.locator("input.grade-input[data-row='1'][data-col='0']")).hasValue("4");
        assertThat(page.locator("table.behavior-grid-root tbody tr").nth(0).locator(".behavior-average-raw"))
                .hasText("2.50");
        assertThat(page.locator("table.behavior-grid-root tbody tr").nth(0).locator("td.average-cell"))
                .hasClass(Pattern.compile(".*borderline.*"));
    }
}
