package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import de.notenfuchs.domain.Feedback;
import de.notenfuchs.domain.Teacher;
import de.notenfuchs.web.LocalAuthTestProfile;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks through a teacher's school year, one test per journey, and is also the source of the
 * user guide in {@code docs/anleitung/} (one page per journey, same order) and the README images.
 *
 * <p>Always runs as a regular test. With {@code -Dscreenshots=true} it also writes the images;
 * run it alone then ({@code ./mvnw verify -Dit.test=WalkthroughIT -Dscreenshots=true}), since the
 * journeys build on each other and on fixed names and dates so the images come out byte-identical.
 * At the end of each journey it checks its guide page both ways: every **„label“** quoted there
 * must have appeared on a page the journey visited, and the page must reference exactly the
 * images the journey takes.
 */
@QuarkusTest
@TestProfile(LocalAuthTestProfile.class)
@WithPlaywright
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalkthroughIT {

    private static final boolean WRITE_SCREENSHOTS = Boolean.getBoolean("screenshots");
    private static final Path GUIDE_DIR = Path.of("docs/anleitung");
    private static final Path GUIDE_IMAGES = GUIDE_DIR.resolve("bilder");
    private static final Path README_IMAGES = Path.of("screenshots");
    private static final Pattern GUIDE_LABEL = Pattern.compile("\\*\\*„([^“]+)“\\*\\*");
    private static final Pattern GUIDE_IMAGE = Pattern.compile("\\(bilder/([\\w-]+)\\.png\\)");

    private static final String CLASS = "7b";
    private static final String YEAR = "2025/26";

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    private Page page;
    private final StringBuilder seenText = new StringBuilder();
    private final Set<String> imagesTaken = new LinkedHashSet<>();

    @BeforeEach
    void login() {
        context.clearCookies();
        page = context.newPage();
        page.setViewportSize(1280, 900);
        // No fades or animations: a screenshot must not depend on how far one has progressed.
        // The local user is always an instance admin; the guide shows a teacher's view, so the
        // admin-only "Rückmeldungen" link is hidden in the images.
        page.addInitScript("document.addEventListener('DOMContentLoaded', () => {"
                + " const style = document.createElement('style');"
                + " style.textContent = '*, *::before, *::after { transition: none !important; animation: none !important; }"
                + " a[href=\\'/admin/feedback\\'] { display: none !important; }';"
                + " document.head.appendChild(style); });");
        page.navigate(baseUrl() + "login");
        page.locator("input[name='j_password']").fill(LocalAuthTestProfile.PASSWORD);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anmelden")).click();
        assertThat(page.locator("h1")).hasText("Übersicht");
    }

    @Test
    @Order(1)
    void schuljahresbeginn() throws IOException {
        AddPanels.open(page, "+ Klasse anlegen");
        page.locator("#name").fill(CLASS);
        page.locator("#schoolYear").fill(YEAR);
        shot("schuljahresbeginn-klasse-anlegen");
        clickButton("Anlegen");
        clickLink(CLASS);

        Path csv = Files.createTempFile("walkthrough-roster", ".csv");
        try {
            Files.writeString(csv, "Vorname;Nachname\r\nIda;Neumann\r\nJonas;Keller\r\nLea;Schmidt\r\nMilan;Weber\r\n",
                    StandardCharsets.UTF_8);
            page.locator("#rosterFile").setInputFiles(csv);
            assertThat(page.locator("tr[data-status='new']")).hasCount(4);
            shot("schuljahresbeginn-import-vorschau");
            clickButton("Import bestätigen");
        } finally {
            Files.deleteIfExists(csv);
        }
        assertThat(page.locator("#student-list-fragment tbody tr:not(.edit-row)")).hasCount(4);

        createSubject("Deutsch", null);
        // The add forms open for an empty class and stay open until the next visit.
        page.reload();
        shot("schuljahresbeginn-klassenseite");

        clickLink("Deutsch");
        shot("schuljahresbeginn-leeres-fach");
        clickButton("Mit Schriftlich 50 % / Mündlich 50 % starten");
        assertThat(button("+ Leistung in Mündlich")).isVisible();

        page.locator(".breadcrumbs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(CLASS).setExact(true)).click();
        createSubject("Englisch", CLASS + " – Deutsch (" + YEAR + ")");
        clickLink("Englisch");
        clickTab("Leistungen & Gewichtung");
        assertThat(page.locator("#category-list-fragment")).containsText("Schriftlich");
        assertThat(page.locator("#category-list-fragment")).containsText("Mündlich");
        record();

        checkGuide("01-schuljahresbeginn.md");
    }

    @Test
    @Order(2)
    void alltag() throws IOException {
        readmeShot("class-list");
        shot("alltag-uebersicht");
        openMyFach("Deutsch");

        clickButton("+ Leistung in Mündlich");
        page.locator("#assessmentName").fill("Mitarbeit Oktober");
        page.locator("#assessmentDate").fill("2025-10-15");
        shot("alltag-neue-leistung");
        dialogButton("Speichern");

        // The cursor starts in the new column; Enter moves down a row and saves the cell left behind.
        assertThat(page.locator("input.grade-input[data-row='0']")).isFocused();
        for (String value : new String[] {"2-", "3", "e", "5"}) {
            page.keyboard().type(value);
            page.keyboard().press("Enter");
        }
        page.locator("input.grade-input[data-row='3']").evaluate("el => el.blur()");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(page.locator("td.grade-cell-absent")).hasCount(1);
        // (2.3 + 3 + 5) / 3: "2-" counts 2.3, the absent cell doesn't count, and no save's answer may
        // overwrite a later one.
        assertThat(page.locator("input.grade-input[data-row='0']")).hasValue("2-");
        assertThat(page.locator(".assessment-average-raw")).hasText("3,43");
        shotOf("alltag-noten-eintragen", page.locator(".grid-wrapper"));

        clickButton("Noten verbergen");
        assertThat(page.locator(".average-final").first()).hasCSS("color", "rgba(0, 0, 0, 0)");
        shotOf("alltag-noten-verbergen", page.locator(".grid-wrapper"));
        // The choice is stored in the browser; later journeys need the grades visible.
        clickButton("Noten zeigen");

        record();
        page.locator("table.grade-grid tr").filter(new Locator.FilterOptions().setHasText("Jonas Keller"))
                .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Details")).click();
        assertThat(page.locator("h2").first()).containsText("Jonas Keller");
        shot("alltag-schuelerseite");

        clickButton("Fehler melden");
        page.locator("#feedbackMessage").fill("Beim Drucken fehlt die Seitenzahl.");
        shot("alltag-fehler-melden");
        page.locator("#feedback-dialog").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Senden")).click();
        assertThat(page.locator(".feedback-thanks")).isVisible();
        record();
        String storedPage = QuarkusTransaction.requiringNew().call(() -> Feedback.<Feedback>find(
                "message", "Beim Drucken fehlt die Seitenzahl.").firstResult().page);
        assertEquals("/subjects/{id}/grid/students/{id}", storedPage);

        checkGuide("02-alltag.md");
    }

    @Test
    @Order(3)
    void klassenarbeitMitPunkten() throws IOException {
        openMyFach("Deutsch");
        page.locator("th.category-header").filter(new Locator.FilterOptions().setHasText("Schriftlich"))
                .locator(".column-add").click();
        page.locator("#assessmentName").fill("Klassenarbeit 1");
        page.locator("#assessmentDate").fill("2025-10-22");
        page.locator("#assessmentFactor").fill("2");
        page.locator("#assessmentPointsBased").check();
        page.locator("#assessmentMaxPoints").fill("60");
        record();
        dialogButton("Speichern");

        assertThat(page.locator("input.points-input[data-row='0']")).isFocused();
        for (String points : new String[] {"52", "38", "45", "22"}) {
            page.keyboard().type(points);
            page.keyboard().press("Enter");
        }
        page.locator("input.points-input[data-row='3']").evaluate("el => el.blur()");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.locator(".column-edit").filter(new Locator.FilterOptions().setHasText("Klassenarbeit 1")).click();
        Locator dialog = page.locator("#assessment-dialog");
        dialog.locator("input[name='bandPoints']").first().fill("50");
        assertThat(dialog.locator(".assessment-dialog-preview")).containsText("1: 1");
        shot("punkte-notenschluessel");
        dialogButton("Speichern");
        assertThat(page.locator(".derived-grade").first()).hasText("1");
        shotOf("punkte-raster", page.locator(".grid-wrapper"));

        clickTab("Notenspiegel");
        assertThat(page.locator("table.distribution tr").filter(new Locator.FilterOptions().setHasText("Klassenarbeit 1"))
                .locator("td.num").last()).hasText("4");
        record();
        shotOf("punkte-notenspiegel", page.locator(".grid-wrapper"));

        checkGuide("03-klassenarbeit-mit-punkten.md");
    }

    @Test
    @Order(4)
    void vorDemZeugnis() throws IOException {
        clickLink(CLASS);
        clickTab("Einstellungen");
        page.locator("#half-year-cutoff-fragment input[name='halfYearCutoff']").fill("2026-01-31");
        page.locator("#half-year-cutoff-fragment").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Speichern")).click();
        assertThat(page.locator("#half-year-cutoff-fragment .saved-note")).hasText("Gespeichert");
        shot("zeugnis-einstellungen");

        clickTab("Fächer & Schüler");
        readmeShot("class-detail");
        clickLink("Deutsch");
        assertThat(page.locator("table.grade-grid-root")).isVisible();
        readmeShot("grade-grid");
        shot("zeugnis-halbjahre");

        // The "+" of the first half proposes a date inside it, even when today is already past the cutoff.
        LocalDate cutoff = LocalDate.parse("2026-01-31");
        String expectedDate = (LocalDate.now().isAfter(cutoff) ? cutoff : LocalDate.now()).toString();
        page.locator("th.category-header .column-add[data-date]").nth(0).click();
        assertThat(page.locator("#assessmentDate")).hasValue(expectedDate);
        page.locator("#assessment-dialog .assessment-dialog-cancel").click();

        // The ✎ at the half-year grade opens the student page with the cursor in that field.
        clickLink("Zeugnisnote 1. Halbjahr für Jonas Keller festlegen");
        assertThat(page.locator("#reportGradeFirstHalf")).isFocused();
        page.locator("#reportGradeFirstHalf").fill("2-");
        page.locator("#reportGradeYear").fill("2");
        page.locator(".student-grades").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Speichern")).click();
        assertThat(page.locator(".student-averages .report-grade")).hasText(new String[] {"2-", "2"});
        shotOf("zeugnis-zeugnisnote", page.locator(".student-grades"));
        record();
        clickLink("← zurück zur Notenerfassung");
        assertThat(page.locator("td.has-report-grade")).hasCount(2);

        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Verhaltensnoten der Klasse")).click();
        String[] deutsch = {"2", "2", "3", "4"};
        for (int row = 0; row < deutsch.length; row++) {
            Locator cell = page.locator("input.grade-input[data-row='" + row + "'][data-col='0']");
            cell.fill(deutsch[row]);
            cell.evaluate("el => el.blur()");
        }
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(page.locator("td.grade-cell-open")).hasCount(4);
        shot("zeugnis-verhaltensnoten");

        clickTab("Notenübersicht");
        assertThat(page.locator("td.at-risk")).hasCount(1);
        shot("zeugnis-notenuebersicht");

        Download download = page.waitForDownload(() -> clickLink("Als Excel exportieren"));
        assertEquals(CLASS + "-Noten.xlsx", download.suggestedFilename());
        // The browser runs in a container, so the file has to be copied over before reading it.
        Path xlsx = Files.createTempFile("walkthrough-export", ".xlsx");
        try {
            download.saveAs(xlsx);
            try (InputStream in = Files.newInputStream(xlsx); Workbook workbook = WorkbookFactory.create(in)) {
                assertEquals(List.of("Notenübersicht", "Deutsch", "Englisch"), sheetNames(workbook));
            }
        } finally {
            Files.deleteIfExists(xlsx);
        }

        checkGuide("04-vor-dem-zeugnis.md");
    }

    @Test
    @Order(5)
    void unterjaehrig() throws IOException {
        seedColleague("walkthrough-kollegin", "Frau Beispiel");
        seedColleague("walkthrough-kollege", "Herr Muster");

        clickLink(CLASS);
        page.onceDialog(Dialog::accept);
        page.locator("#student-list-fragment tr").filter(new Locator.FilterOptions().setHasText("Milan Weber"))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Ändern")).click();
        page.locator("tr.edit-row.editing").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Ausgetreten")).click();
        assertThat(page.locator(".left-student-list")).containsText("Milan Weber");
        record();

        clickLink("Deutsch");
        assertThat(page.locator("tbody.left-students")).containsText("Milan Weber");
        shotOf("unterjaehrig-ausgetreten", page.locator(".grid-wrapper"));

        clickTab("Lehrkräfte");
        AddPanels.open(page, "+ Lehrkraft hinzufügen");
        page.locator("#teacherSubject").selectOption(new SelectOption().setLabel("Frau Beispiel"));
        clickButton("Hinzufügen");
        assertThat(page.locator("#subject-teachers-fragment tbody tr")).hasCount(2);
        shot("unterjaehrig-lehrkraefte");

        checkGuide("05-unterjaehrig.md");
    }

    @Test
    @Order(6)
    void neuesSchuljahr() throws IOException {
        clickLink(CLASS);
        clickTab("Einstellungen");
        page.locator("#newClassName").fill("8b");
        page.locator("#newSchoolYear").fill("2026/27");
        shot("neues-schuljahr-uebernehmen");
        clickButton("Ins neue Schuljahr übernehmen");

        assertThat(page.locator("h1")).containsText("8b");
        // Milan left during the year, so only the other three move up.
        assertThat(page.locator("#student-list-fragment tbody tr:not(.edit-row)")).hasCount(3);
        shot("neues-schuljahr-neue-klasse");

        page.locator(".breadcrumbs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Übersicht")).click();
        assertThat(page.locator("a.class-card").filter(new Locator.FilterOptions().setHasText("8b – Deutsch"))).hasCount(1);
        record();

        checkGuide("06-neues-schuljahr.md");
    }

    // ---- steps shared by the journeys ----

    /** Colleagues normally appear after their first login; here they are seeded directly. */
    private static void seedColleague(String subject, String name) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Teacher.count("subject", subject) == 0) {
                Teacher colleague = new Teacher();
                colleague.subject = subject;
                colleague.displayName = name;
                colleague.firstSeenAt = Instant.parse("2025-09-01T08:00:00Z");
                colleague.lastSeenAt = colleague.firstSeenAt;
                colleague.persist();
            }
        });
    }

    private void createSubject(String name, String copyCategoriesFrom) {
        AddPanels.open(page, "+ Fach anlegen");
        page.locator("#subjectName").fill(name);
        page.locator("#gradeScaleId").selectOption(new SelectOption().setLabel("DE 1-6"));
        if (copyCategoriesFrom != null) {
            page.locator("#copyCategoriesFrom").selectOption(new SelectOption().setLabel(copyCategoriesFrom));
        }
        record();
        clickButton("Anlegen");
        assertThat(page.locator("#subject-list-fragment")).containsText(name);
    }

    private void openMyFach(String subject) {
        page.navigate(baseUrl() + "classes");
        page.locator("a.class-card").filter(new Locator.FilterOptions().setHasText(CLASS + " – " + subject)).click();
        assertThat(page.locator("h1")).containsText(CLASS + " – " + subject);
        record();
    }

    private void clickButton(String name) {
        record();
        button(name).click();
    }

    private Locator button(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private void dialogButton(String name) {
        record();
        page.locator("#assessment-dialog").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name).setExact(true)).click();
    }

    private void clickLink(String name) {
        record();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name).setExact(true)).first().click();
    }

    private void clickTab(String name) {
        record();
        page.locator(".tabs").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(name).setExact(true)).click();
    }

    /** Remembers the page's text (hidden dialogs included) for the guide's label check. */
    private void record() {
        seenText.append(' ').append(page.evaluate("() => document.body.textContent"));
    }

    /**
     * A screenshot must not catch anything that depends on timing: the green flash after a save,
     * a hover color under the mouse that just clicked, or a focus ring.
     */
    private void settle() {
        page.waitForFunction("() => !document.querySelector('.state-saved, .state-saving')");
        page.mouse().move(0, 0);
        page.evaluate("() => document.activeElement && document.activeElement.blur()");
    }

    private void shot(String name) throws IOException {
        settle();
        record();
        imagesTaken.add(name);
        if (WRITE_SCREENSHOTS) {
            write(GUIDE_IMAGES.resolve(name + ".png"), page.screenshot(new Page.ScreenshotOptions()));
        }
    }

    private void shotOf(String name, Locator element) throws IOException {
        settle();
        record();
        imagesTaken.add(name);
        if (WRITE_SCREENSHOTS) {
            write(GUIDE_IMAGES.resolve(name + ".png"), element.screenshot(new Locator.ScreenshotOptions()));
        }
    }

    private void readmeShot(String name) throws IOException {
        settle();
        record();
        if (WRITE_SCREENSHOTS) {
            write(README_IMAGES.resolve(name + ".png"), page.screenshot(new Page.ScreenshotOptions().setFullPage(true)));
        }
    }

    /**
     * Re-encodes through ImageIO (Chromium's encoder isn't byte-stable, the JDK's is for identical
     * pixels) and keeps the existing file when the new one differs only by rendering noise, so an
     * unchanged UI leaves {@code git status} clean.
     */
    private static void write(Path file, byte[] rawPng) throws IOException {
        Files.createDirectories(file.getParent());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(rawPng));
        if (Files.exists(file) && looksTheSame(ImageIO.read(file.toFile()), image)) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        Files.write(file, out.toByteArray());
    }

    /** Chromium's anti-aliasing varies by a few color levels between runs; anything visible is more. */
    private static boolean looksTheSame(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    if (Math.abs(((p >> shift) & 0xff) - ((q >> shift) & 0xff)) > 8) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void checkGuide(String guideFile) throws IOException {
        String guide = Files.readString(GUIDE_DIR.resolve(guideFile), StandardCharsets.UTF_8);
        String seen = normalize(seenText.toString());
        List<String> missing = new ArrayList<>();
        Matcher labels = GUIDE_LABEL.matcher(guide);
        while (labels.find()) {
            if (!seen.contains(normalize(labels.group(1)))) {
                missing.add(labels.group(1));
            }
        }
        assertTrue(missing.isEmpty(), guideFile + " quotes labels the app no longer shows: " + missing);

        Set<String> referenced = new LinkedHashSet<>();
        Matcher images = GUIDE_IMAGE.matcher(guide);
        while (images.find()) {
            referenced.add(images.group(1));
        }
        assertEquals(imagesTaken, referenced, guideFile + " must reference exactly the images this journey takes");
    }

    private static String normalize(String text) {
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ");
    }

    private static List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    private String baseUrl() {
        return "http://host.testcontainers.internal:" + rootUrl.getPort() + "/";
    }
}
