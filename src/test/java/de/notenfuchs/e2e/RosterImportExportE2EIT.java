package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import de.notenfuchs.service.CsvRosterService;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser-driven end-to-end test of roster CSV import/export through the real server-rendered
 * Qute/HTMX UI ({@code ClassUiResource}'s {@code /roster/*} endpoints). Same style as
 * {@link GradeGridE2EIT}: a Failsafe IT (./mvnw verify), needs a real running app, a browser
 * (quarkus-playwright Dev Services) and Postgres (Testcontainers Dev Services).
 *
 * <p>Each test creates its own uniquely-named class/students so it stays independent of other
 * tests and pre-existing data without needing a DB reset between tests.
 */
@QuarkusTest
@WithPlaywright
class RosterImportExportE2EIT {

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    private Page page;

    private final CsvRosterService csvRosterService = new CsvRosterService();

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    private String baseUrl() {
        return "http://host.testcontainers.internal:" + rootUrl.getPort() + "/";
    }

    @Test
    void importPreviewMarksNewAndDuplicateRowsThenConfirmCreatesOnlyNewStudents() throws IOException {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-Roster-Klasse-" + unique;
        String existingName = "E2E-Bestehend-" + unique;
        String newName1 = "E2E-Neu1-" + unique;
        String newName2 = "E2E-Neu2-" + unique;

        createClassWithStudent(className, existingName);

        Path csvFile = Files.createTempFile("roster-import-" + unique, ".csv");
        try {
            String csv = "Name;Klasse\r\n" + existingName + ";5a\r\n" + newName1 + ";5a\r\n\r\n" + newName2 + ";5a\r\n";
            Files.write(csvFile, csv.getBytes(StandardCharsets.UTF_8));

            page.locator("#rosterFile").setInputFiles(csvFile);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Vorschau anzeigen")).click();

            // Preview page: one duplicate (the pre-existing student) and two new rows, one
            // blank line skipped.
            assertThat(page.locator("h1")).hasText("Schüler-Import Vorschau");
            assertThat(page.locator("tr[data-status='duplicate']")).hasCount(1);
            assertThat(page.locator("tr[data-status='new']")).hasCount(2);
            assertThat(page.locator("tr[data-status='duplicate']")).containsText(existingName);
            Locator newRows = page.locator("tr[data-status='new']");
            assertThat(newRows.nth(0)).containsText(newName1);
            assertThat(newRows.nth(1)).containsText(newName2);
            assertThat(page.locator(".hint").first()).containsText("2 neu");
            assertThat(page.locator(".hint").first()).containsText("1 Duplikate");
            assertThat(page.locator(".hint").first()).containsText("1 leere Zeilen übersprungen");

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Import bestätigen")).click();

            // Back on the class page: exactly one row per name - the duplicate wasn't
            // re-created - and a result message reporting created/skipped counts.
            assertThat(page.getByText("2 Schüler angelegt, 1 übersprungen")).isVisible();
            assertThat(studentRow(existingName)).hasCount(1);
            assertThat(studentRow(newName1)).hasCount(1);
            assertThat(studentRow(newName2)).hasCount(1);
        } finally {
            Files.deleteIfExists(csvFile);
        }
    }

    @Test
    void exportDownloadsCsvWithAllStudentNames() throws IOException {
        String unique = Long.toString(System.nanoTime());
        String className = "E2E-Roster-Export-Klasse-" + unique;
        String studentName = "E2E-Export-Schueler-" + unique;

        createClassWithStudent(className, studentName);

        Download download = page.waitForDownload(() ->
                page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Als CSV exportieren")).click());
        assertTrue(download.suggestedFilename().endsWith(".csv"));

        Path savedPath = Files.createTempFile("notenfuchs-roster-export-" + unique, ".csv");
        try {
            download.saveAs(savedPath);
            byte[] content = Files.readAllBytes(savedPath);

            List<String> names = csvRosterService.parse(content);
            assertTrue(names.contains(studentName), "expected " + studentName + " in exported names " + names);

            // German-Excel-friendly dialect: UTF-8 BOM + semicolon delimiter.
            assertTrue(content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                    && (content[2] & 0xFF) == 0xBF, "expected a UTF-8 BOM at the start of the export");
        } finally {
            Files.deleteIfExists(savedPath);
        }
    }

    private Locator studentRow(String name) {
        return page.locator("#student-list-fragment table tbody tr").filter(new Locator.FilterOptions().setHasText(name));
    }

    private void createClassWithStudent(String className, String studentName) {
        page.navigate(baseUrl());
        page.locator("#name").fill(className);
        page.locator("#schoolYear").fill("2025/26");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(className)).click();

        page.locator("#studentName").fill(studentName);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hinzufügen")).click();
        assertThat(studentRow(studentName)).hasCount(1);
    }
}
