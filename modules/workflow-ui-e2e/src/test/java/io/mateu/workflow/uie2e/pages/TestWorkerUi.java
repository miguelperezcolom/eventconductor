package io.mateu.workflow.uie2e.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The test worker's two pages, and getting to them.
 *
 * <p>Selectors live here for the reason {@link WorkflowUi} gives: Mateu emits a fresh UUID as the
 * id of every component on every render, so the only stable handles are the custom element names
 * and the words on screen. Three things learned from the rendered page are worth writing down,
 * because none of them is guessable:
 *
 * <ul>
 *   <li>The Crud is a {@code mateu-table-crud} — a list beside an inline detail panel — not a
 *       {@code vaadin-grid}. A test that waits for a grid waits until its timeout.</li>
 *   <li>Rows are <b>buttons</b> whose accessible name is the record's id.</li>
 *   <li>Everything lives inside nested open shadow roots, so {@code document.body.innerText} shows
 *       only the page title. Role and text selectors pierce those roots; reading innerText does
 *       not, which makes a dump of the body look like an empty page.</li>
 * </ul>
 */
public class TestWorkerUi {

    private final Page page;

    public TestWorkerUi(Page page) {
        this.page = page;
    }

    /** Opens Test worker → Received tasks. */
    public TestWorkerUi goToReceivedTasks() {
        open("Received tasks");
        return this;
    }

    /** Opens Test worker → Task overrides. */
    public TestWorkerUi goToTaskOverrides() {
        open("Task overrides");
        return this;
    }

    private void open(String item) {
        // Opened and clicked with nothing in between: listing the menu's items re-renders the
        // overlay, and the click then races a submenu that is being rebuilt.
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Test worker")).click();
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(item)).click();
        assertThat(text(item)).isVisible();
    }

    /** A row in the list, addressed the way the UI exposes it: a button named after the record. */
    public Locator row(String id) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(id)).first();
    }

    /** Opens a record's detail panel. */
    public TestWorkerUi openRow(String id) {
        row(id).click();
        return this;
    }

    /**
     * Any text on the page — the page title, or a value in the detail panel.
     *
     * <p>Not an exact match, on purpose. A list row renders its title and subtitle into one text
     * node, so a row whose name is "slow charge-card down" reads as "slow charge-card down booking"
     * and an exact matcher finds nothing.
     */
    public Locator text(String value) {
        return page.getByText(value).first();
    }

    /** The buttons the page offers, which is how "this page cannot create records" is asserted. */
    public Locator button(String label) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label));
    }
}
