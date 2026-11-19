package de.notenfuchs.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Rarely used forms sit in a closed {@code details.add-panel} that is open only while its list is
 * empty. Tests share one database, so whether a panel starts open differs between runs.
 */
final class AddPanels {

    private AddPanels() {
    }

    /** Opens the panel whose button reads {@code summary}, unless it is open already. */
    static void open(Page page, String summary) {
        Locator panel = page.locator("details.add-panel").filter(new Locator.FilterOptions()
                .setHas(page.locator("summary", new Page.LocatorOptions().setHasText(summary))));
        if (panel.getAttribute("open") == null) {
            panel.locator("summary").click();
        }
    }
}
