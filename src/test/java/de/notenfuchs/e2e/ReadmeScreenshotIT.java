package de.notenfuchs.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import de.notenfuchs.web.LocalAuthTestProfile;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Generates the PNG screenshots embedded in README.md, driven through the real rendered UI the
 * same way as the other Playwright ITs in this package. Deliberately <b>not</b> part of the
 * default {@code ./mvnw verify} run (excluded in pom.xml's failsafe {@code <excludes>}) since
 * it's a manual, on-demand content-generation step, not a regression test - run it explicitly
 * whenever the UI changes enough to make the current screenshots stale:
 *
 * <pre>./mvnw verify -Dit.test=ReadmeScreenshotIT</pre>
 *
 * then {@code git diff --stat screenshots/} and commit only if something actually changed. Logs
 * in via {@link LocalAuthTestProfile} (rather than relying on %test's permit-all bypass) so the
 * nav shows a real "Angemeldet als lehrer" instead of the "Dev-Modus" badge - these screenshots
 * are meant to look like a real deployment.
 *
 * <p>The showcased content is "Demo-Klasse 8b", seeded once and forever by
 * {@code V3__seed_demo_class.sql} and granted to the fixed local-auth "lehrer" login this test
 * signs in as (see that migration's own comments - it's frozen, Flyway-checksummed, never
 * edited again) - deliberately reused here rather than building a parallel fixture, since it
 * already shows off real features a hand-rolled one-category grid wouldn't (a points-based
 * Notenschlüssel column, the Halbjahr split view). This test only adds one extra, otherwise
 * untouched class so the class list doesn't look like a single-class toy install.
 *
 * <p>Each captured PNG is decoded and re-encoded once through {@link ImageIO} before being
 * written to disk. Chromium's own screenshot encoder isn't guaranteed to be byte-stable across
 * runs, and re-running this generator with no visible UI change should produce a byte-identical
 * file (so {@code git status} stays clean) rather than a spurious diff from encoder noise or
 * incidental metadata (e.g. a timestamp chunk) - a plain decode/re-encode round-trip through the
 * JDK's own PNG codec is deterministic for identical pixel data and drops any such metadata as a
 * side effect, without needing an external image-optimizer tool installed.
 *
 * <p>{@code V3__seed_demo_class.sql} seeds the class's {@code halfYearCutoff} and its
 * assessments' {@code date}s relative to {@code CURRENT_DATE} (so a fresh install always looks
 * "recent"), which would otherwise make {@code grade-grid.png} drift with the calendar - not
 * just the displayed "Halbjahres-Stichtag" text, but potentially the Ohne-Datum/1.-HJ/2.-HJ
 * column split itself, since the relative dates stay fixed relative to each other but not to a
 * pinned cutoff. This test overwrites the cutoff and every assessment date to fixed calendar
 * dates via the app's own edit UI ({@link #setHalfYearCutoff}, {@link #setAssessmentDate}) right
 * after reaching the demo class, so the resulting grid is fully deterministic regardless of
 * which day this generator is actually run on.
 */
@QuarkusTest
@TestProfile(LocalAuthTestProfile.class)
@WithPlaywright
class ReadmeScreenshotIT {

    private static final Path SCREENSHOT_DIR = Paths.get("screenshots");

    // Fixed replacements for V3__seed_demo_class.sql's CURRENT_DATE-relative values, preserving
    // that migration's own relative ordering (Klassenarbeit 1 < Mitarbeit < cutoff <
    // Klassenarbeit 2) so the grid still splits into the same Ohne-Datum/1.-HJ/2.-HJ shape.
    private static final String KLASSENARBEIT_1_DATE = "2025-11-03";
    private static final String MITARBEIT_DATE = "2025-11-17";
    private static final String HALF_YEAR_CUTOFF = "2025-11-20";
    private static final String KLASSENARBEIT_2_DATE = "2025-11-24";

    @TestHTTPResource("/")
    URL rootUrl;

    @InjectPlaywright
    BrowserContext context;

    private Page page;

    @BeforeEach
    void openPage() {
        context.clearCookies();
        page = context.newPage();
        page.setViewportSize(1280, 900);
    }

    private String baseUrl() {
        return "http://host.testcontainers.internal:" + rootUrl.getPort() + "/";
    }

    @Test
    void generatesReadmeScreenshots() throws IOException {
        Files.createDirectories(SCREENSHOT_DIR);

        login();

        createClass("Klasse 9a", "2025/26");
        screenshotFullPage("class-list");

        // .setExact(true): "Klasse 9a" above doesn't collide, but "Demo-Klasse 8b" would
        // otherwise also match a plain-substring lookup for anything ending the same way.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Demo-Klasse 8b").setExact(true)).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Demo-Klasse 8b"))).isVisible();

        // Viewport-only (not full-page): the further-down admin sections (Verhaltensnoten,
        // Notenübersicht, Klasseneinstellungen, Lehrkräfte) are real but would clutter a
        // README "hero" screenshot - this crops to the Fächer/Schüler management view above
        // the fold, like a product screenshot rather than an exhaustive page dump.
        screenshotViewport("class-detail");

        setHalfYearCutoff(HALF_YEAR_CUTOFF);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Mathematik")).click();
        setAssessmentDate("Klassenarbeit 1", KLASSENARBEIT_1_DATE);
        setAssessmentDate("Mitarbeit 1. Halbjahr", MITARBEIT_DATE);
        setAssessmentDate("Klassenarbeit 2 (Punkte)", KLASSENARBEIT_2_DATE);

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Notenerfassung")).click();
        assertThat(page.locator("table.grade-grid-root")).isVisible();

        screenshotFullPage("grade-grid");
    }

    private void setHalfYearCutoff(String isoDate) {
        page.locator("#half-year-cutoff-fragment .rename-toggle").click();
        page.locator("#half-year-cutoff-fragment input[name='halfYearCutoff']").fill(isoDate);
        page.locator("#half-year-cutoff-fragment .rename-save").click();
        assertThat(page.locator("#half-year-cutoff-fragment .rename-display")).hasText(isoDate);
    }

    private void setAssessmentDate(String assessmentName, String isoDate) {
        Locator row = page.locator("table.entity-list tbody tr.assessment-row")
                .filter(new Locator.FilterOptions().setHasText(assessmentName)).first();
        String targetId = row.locator(".rename-toggle").getAttribute("data-rename-target");
        row.locator(".rename-toggle").click();

        Locator editRow = page.locator("#" + targetId);
        editRow.locator("input[name='date']").fill(isoDate);
        editRow.locator(".rename-save").click();

        Locator updatedRow = page.locator("table.entity-list tbody tr.assessment-row")
                .filter(new Locator.FilterOptions().setHasText(assessmentName)).first();
        assertThat(updatedRow.locator(".rename-display").last()).hasText(isoDate);
    }

    private void login() {
        page.navigate(baseUrl() + "login");
        page.locator("input[name='j_password']").fill(LocalAuthTestProfile.PASSWORD);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anmelden")).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Klassen"))).isVisible();
    }

    private void createClass(String name, String schoolYear) {
        page.navigate(baseUrl());
        page.locator("#name").fill(name);
        page.locator("#schoolYear").fill(schoolYear);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Anlegen")).click();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }

    private void screenshotFullPage(String name) throws IOException {
        writeScreenshot(name, page.screenshot(new Page.ScreenshotOptions().setFullPage(true)));
    }

    private void screenshotViewport(String name) throws IOException {
        writeScreenshot(name, page.screenshot(new Page.ScreenshotOptions().setFullPage(false)));
    }

    private void writeScreenshot(String name, byte[] rawPng) throws IOException {
        Files.write(SCREENSHOT_DIR.resolve(name + ".png"), normalize(rawPng));
    }

    private static byte[] normalize(byte[] rawPng) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(rawPng));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
