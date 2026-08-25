package io.mateu.workflow.uie2e.pages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The application shell: the menu bar the engine contributes, and getting from it to a page.
 *
 * <p><b>Why page objects at all here.</b> The UI exposes no test hooks — Mateu emits a fresh UUID
 * as the id of every component on every render, so there is nothing stable to select by except the
 * custom element names and the words an operator reads on screen. Selecting by visible text is the
 * right thing for a test that claims to simulate a user, but it does couple these tests to the
 * UI's copy. Keeping every such selector in this package means a renamed button is one edit here
 * rather than an edit in each test, and it is where {@code data-testid} would land if the
 * framework ever grows them.
 */
public class WorkflowUi {

    private final Page page;

    public WorkflowUi(Page page) {
        this.page = page;
    }

    /**
     * Waits for the shell to be interactive. The first paint is a client-side render behind a
     * couple of round trips, so without this the first click of a test races the menu into
     * existence.
     */
    public void awaitLoaded() {
        assertThat(menuRoot()).isVisible();
    }

    private com.microsoft.playwright.Locator menuRoot() {
        return page.locator("vaadin-menu-bar").first();
    }

    /** Opens Workflow → Processes and returns the page object for what lands. */
    public ProcessesPage goToProcesses() {
        openWorkflowMenu();
        clickMenuItem("Processes");
        var processes = new ProcessesPage(page);
        processes.awaitLoaded();
        return processes;
    }

    /** Opens Workflow → Definitions. */
    public void goToDefinitions() {
        openWorkflowMenu();
        clickMenuItem("Definitions");
    }

    /** Opens Workflow → Steps. */
    public void goToSteps() {
        openWorkflowMenu();
        clickMenuItem("Steps");
    }

    public void openWorkflowMenu() {
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Workflow")).click();
    }

    private void clickMenuItem(String label) {
        // The submenu is rendered into an overlay outside the menu bar, so it is found from the
        // page rather than from the menu root.
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(label)).click();
    }

    /**
     * The words the Workflow menu offers — the engine's own navigation contract.
     *
     * <p>Read from the open submenu overlay rather than from the whole page. Collecting every
     * {@code menuitem} in the document worked only while the engine's was the one menu in the
     * shell; the moment a second one was mounted beside it — the test worker's — its items were
     * counted as the engine's, and this became an assertion about the whole application rather
     * than about the menu it names.
     */
    public java.util.List<String> workflowMenuItems() {
        openWorkflowMenu();
        // vaadin-menu-bar-item is a submenu entry; the bar's own top-level buttons are
        // vaadin-menu-bar-button. Only the open submenu is rendered, so this is exactly the
        // items of the menu just opened — and it stays right however many menus the shell has.
        var items = page.locator("vaadin-menu-bar-item");
        items.first().waitFor();
        return items.allInnerTexts().stream()
                .map(String::trim)
                .filter(text -> !text.isBlank() && !"···".equals(text))
                .toList();
    }
}
