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
 * Browser-driven end-to-end coverage for the "copy class into a new school year" action
 * (the "Ins neue Schuljahr übernehmen" form on {@code ClassPage/detail.html}, backed by
 * {@code ClassUiResource#duplicate}). Complements {@code de.notenfuchs.web.ClassDuplicationIT},
 * which asserts the copy semantics at the HTTP/data level - this test instead drives the real
 * UI flow a teacher would use, and confirms the source class stays untouched and both classes
 * remain independently editable afterwards. A Failsafe IT (./mvnw verify) for the same reasons
 * as {@link GradeGridE2EIT}: needs a real running app, a browser (quarkus-playwright Dev
 * Services) and Postgres (Testcontainers Dev Services).
 */
@QuarkusTest
@WithPlaywright
class ClassDuplicationE2EIT {

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
    void duplicatingAClassCopiesStructureAndLeavesBothClassesEditable() {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-Dup-Klasse-" + unique;
        String subjectName = "E2E-Dup-Fach-" + unique;
        String categoryName = "E2E-Dup-Kategorie-" + unique;
        String assessmentName = "E2E-Dup-Leistung-" + unique;
        String studentName = "E2E-Dup-Schueler-" + unique;
        String newClassName = "E2E-Dup-Klasse-9b-" + unique;
        String newSchoolYear = "2026/27";

        createClass(className);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        createSubject(subjectName);

        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(studentRow(studentName)).hasCount(1);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        page.locator("#categoryName").fill(categoryName);
        page.locator("#weightPercent").fill("100");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();

        Locator categoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        categoryCard.locator("form.inline-form input[name='name']").fill(assessmentName);
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Leistung hinzufügen")).click();
        assertThat(categoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName)))
                .isVisible();

        // Back to the class detail page (breadcrumb), where the duplicate action lives.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#newClassName").fill(newClassName);
        page.locator("#newSchoolYear").fill(newSchoolYear);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Ins neue Schuljahr übernehmen")).click();

        // Landed on the new class's own detail page.
        assertThat(page.locator("h1")).containsText(newClassName);
        assertThat(page.locator(".hint").filter(new Locator.FilterOptions().setHasText("Nachfolger von"))).containsText(className);

        // Subject and student copied over.
        assertThat(page.locator(".subject-list-item").filter(new Locator.FilterOptions().setHasText(subjectName))).isVisible();
        assertThat(studentRow(studentName)).hasCount(1);

        // Fresh start: the copied subject's category has no Leistungen.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        Locator newCategoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(newCategoryCard.locator("table.entity-list tbody tr.empty-row")).isVisible();
        assertThat(newCategoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName)))
                .hasCount(0);

        // New class is independently editable: add a student to it.
        String newClassExtraStudent = "E2E-Dup-Neu-Extra-" + unique;
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(newClassName)).click();
        page.locator("#studentName").fill(newClassExtraStudent);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(studentRow(newClassExtraStudent)).hasCount(1);

        // The source class stayed untouched (its Leistung is still there) and stays editable too.
        page.navigate(baseUrl() + "classes");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        assertThat(studentRow(studentName)).hasCount(1);
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(subjectName)).click();
        Locator sourceCategoryCard = page.locator(".card").filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(sourceCategoryCard.locator("table.entity-list tbody tr").filter(new Locator.FilterOptions().setHasText(assessmentName)))
                .isVisible();

        String sourceClassExtraStudent = "E2E-Dup-Alt-Extra-" + unique;
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();
        page.locator("#studentName").fill(sourceClassExtraStudent);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(studentRow(sourceClassExtraStudent)).hasCount(1);
    }

    private Locator studentRow(String name) {
        return page.locator("#student-list-fragment table tbody tr").filter(new Locator.FilterOptions().setHasText(name));
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
