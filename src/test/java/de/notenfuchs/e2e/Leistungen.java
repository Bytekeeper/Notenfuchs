package de.notenfuchs.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** Creates a Leistung on "Leistungen & Gewichtung" through the Leistung dialog. */
final class Leistungen {

    private Leistungen() {
    }

    /** {@code date} null leaves the Leistung undated (the dialog would preset today). */
    static void add(Page page, Locator categoryCard, String name, String date, boolean pointsBased) {
        categoryCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("+ Leistung anlegen")).click();
        Locator dialog = page.locator("#assessment-dialog");
        dialog.locator("#assessmentName").fill(name);
        dialog.locator("#assessmentDate").fill(date == null ? "" : date);
        if (pointsBased) {
            dialog.locator("#assessmentPointsBased").check();
            dialog.locator("#assessmentMaxPoints").fill("60");
        }
        // Saving reloads the page; wait for it so the next step doesn't act on the old one.
        page.waitForNavigation(() -> dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Speichern")).click());
    }
}
