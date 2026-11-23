package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * "Noten verbergen": with the switch on, a cell with a grade and an absence mark looks like an empty
 * one, and an at-risk grade loses its red. One click or focus shows a single value.
 */
@QuarkusTest
@WithPlaywright
class PrivacyModeE2EIT {

    private static final String TRANSPARENT = "rgba(0, 0, 0, 0)";
    private static final String TONAL = "rgb(240, 239, 234)";

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    @Test
    void hidesGradesAndTheirHighlightsUntilOneIsShown() {
        Page page = context.newPage();
        String unique = Long.toString(System.nanoTime());
        String classUrl = setUpGrid(page, unique);

        Locator graded = page.locator("input.grade-input[data-col='0']");
        Locator empty = page.locator("input.grade-input[data-col='1']");
        graded.fill("5 u");
        graded.evaluate("el => el.blur()");
        assertThat(graded.locator("xpath=..")).hasClass(Pattern.compile("grade-cell-absent"));
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Noten verbergen")).click();
        Locator showButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Noten zeigen"));
        assertThat(showButton).isVisible();

        Locator gradedCell = graded.locator("xpath=..");
        Locator emptyCell = empty.locator("xpath=..");
        Locator average = page.locator(".average-final");
        assertEquals(style(emptyCell, "backgroundColor"), style(gradedCell, "backgroundColor"));
        assertEquals(TRANSPARENT, style(graded, "color"));
        assertEquals(TRANSPARENT, style(average, "color"));

        // The choice survives a reload, without a flash: privacy.js runs before the page paints.
        page.reload();
        assertThat(showButton).isVisible();
        assertEquals(TRANSPARENT, style(average, "color"));

        // Focus shows the cell being typed in; a click shows one value until the next click.
        graded.focus();
        assertNotEquals(TRANSPARENT, style(graded, "color"));
        graded.evaluate("el => el.blur()");
        assertEquals(TRANSPARENT, style(graded, "color"));
        average.click();
        assertNotEquals(TRANSPARENT, style(average, "color"));
        page.locator(".grid-legend").click();
        assertEquals(TRANSPARENT, style(average, "color"));

        // The Notenübersicht: the at-risk 5 is neither red nor readable.
        page.navigate(classUrl + "/overview");
        Locator atRisk = page.locator("td.at-risk");
        assertThat(atRisk).hasCount(1);
        assertEquals(TONAL, style(atRisk, "backgroundColor"));
        assertEquals(TRANSPARENT, style(atRisk.locator(".average-final"), "color"));
        assertEquals(TRANSPARENT, style(page.locator(".overview-summary"), "color"));

        showButton.click();
        assertNotEquals(TONAL, style(atRisk, "backgroundColor"));
        assertThat(atRisk.locator(".average-final")).hasText("5");
    }

    private static String style(Locator locator, String property) {
        return (String) locator.evaluate("(el, p) => getComputedStyle(el)[p]", property);
    }

    /** One student, one Fach with two undated Leistungen; returns the class page URL and stays on the grid. */
    private String setUpGrid(Page page, String unique) {
        String className = "E2E-Privacy-" + unique;
        String subjectName = "E2E-Fach-" + unique;
        String categoryName = "E2E-Kategorie-" + unique;

        page.navigate("http://host.testcontainers.internal:" + rootUrl.getPort() + "/");
        AddPanels.open(page, "+ Klasse anlegen");
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        String classUrl = page.url();

        page.locator("#studentName").fill("Anna-" + unique);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(page.locator("#student-list-fragment")).containsText("Anna-" + unique);
        page.locator("#subjectName").fill(subjectName);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen").setExact(true)).click();

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Leistungen & Gewichtung")).click();
        AddPanels.open(page, "+ Kategorie anlegen");
        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.locator("form:has(#categoryName) button[type='submit']").click();
        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        Leistungen.add(page, categoryCard, "Test1", null, false);
        Leistungen.add(page, categoryCard, "Test2", null, false);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
        return classUrl;
    }
}
