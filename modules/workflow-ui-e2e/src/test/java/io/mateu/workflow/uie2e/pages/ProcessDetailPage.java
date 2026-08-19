package io.mateu.workflow.uie2e.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * One process: its status badge, its tabs, and the operator actions in its toolbar.
 *
 * <p>The action labels here are the ones the engine declares on
 * {@code SimpleProcessViewModel} — two of them through {@code @Label}, the rest derived from the
 * method name. They are what an operator reads, so they are what the tests click.
 */
public class ProcessDetailPage {

    public static final String PAUSE = "Pause process";
    public static final String RESUME = "Resume process";
    public static final String CANCEL = "Cancel process";
    public static final String RETRY_FROM_FAILURE = "Retry from failure";
    public static final String RESTART_FROM_BEGINNING = "Restart from the beginning";

    private final Page page;

    public ProcessDetailPage(Page page) {
        this.page = page;
    }

    public void awaitLoaded() {
        assertThat(page.getByText("Back to list")).isVisible();
    }

    /**
     * The status badge under the title. Asserted against with {@code containsText}, because the
     * badge carries progress as well — "Running (33%)" — and the percentage is not the point.
     */
    public Locator statusBadge() {
        return page.locator("mateu-content-header").getByText(
                java.util.regex.Pattern.compile("(Running|Completed|Compensated|Error|Paused|Cancelled)")).first();
    }

    /** Selects one of Diagram / Steps / Messages / Errors / Resources / Variables. */
    public ProcessDetailPage openTab(String name) {
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName(name)).click();
        return this;
    }

    public Locator tab(String name) {
        return page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName(name));
    }

    /**
     * A node of the rendered graph, found by the step name the definition gives it. Located by text
     * rather than through the {@code svg} element: the page is full of icons, which are svg too, so
     * "the first svg" is whichever icon happens to render first.
     */
    public Locator node(String stepName) {
        return page.getByText(stepName, new Page.GetByTextOptions().setExact(true)).first();
    }

    /**
     * The live state the diagram is drawing, read off the element that draws it.
     *
     * <p>Deliberately the attribute and not the picture. The graph is a custom element whose data
     * lives in its attributes, and that is exactly where the interesting failure is: a Mateu
     * {@code State} update carries values, not component metadata, so a page that refreshes itself
     * through one leaves this attribute untouched while the process behind it runs to completion.
     * Asserting on the rendered SVG would not see the difference — the nodes are all still there,
     * drawn with the state they had when the tab was opened.
     *
     * <p>Null when the process has no step executions yet and the engine sends no overlay at all.
     */
    public String diagramOverlay() {
        return page.locator("eventconductor-workflow-graph").first().getAttribute("overlay");
    }

    /**
     * Something the open tab shows. The tabs render different things — a table of steps, a list of
     * errors, a set of variables — so this asks the page rather than assuming a grid.
     *
     * <p>Restricted to what is <em>visible</em>, and that is not a detail. Selecting a tab does not
     * remove the others from the DOM, so a step id like {@code greet} matches the row in the Steps
     * table and also the label under the node the Diagram drew. Without the visibility filter the
     * first match is whichever the framework rendered first, which is usually the hidden one, and
     * the test fails while the screenshot shows the text plainly on screen.
     */
    public Locator tabShows(String text) {
        return page.locator("text=\"" + text + "\" >> visible=true").first();
    }

    /** The workflow name, which the detail renders as the value of a field rather than as text. */
    public Locator name() {
        return page.getByLabel("Name");
    }

    /**
     * An operator action, whether it is on the toolbar or folded away behind its expander.
     *
     * <p>Three things about this toolbar make a naive lookup fail. It does not show every action at
     * once — at the width these tests run, some sit behind a "⋯" expander. That expander is a
     * <b>toggle</b>, not an "open": calling it twice puts the toolbar back, so a lookup that
     * expands unconditionally hides the very button a previous lookup revealed. And the engine only
     * offers the actions that apply to the process as it is now, so "Resume process" genuinely does
     * not exist while the process is running.
     *
     * <p>Hence: look in the state the toolbar is in, toggle, look again, and leave it wherever the
     * button was found.
     */
    public Locator action(String label) {
        if (!offers(label)) {
            throw new AssertionError("The toolbar does not offer '" + label
                    + "' for this process in its current state");
        }
        return button(label).first();
    }

    /**
     * Whether the action is offered at all right now.
     *
     * <p>The "⋯" control pages through the buttons that do not fit rather than opening a menu of
     * them, so this presses it until the label turns up or the toolbar has been all the way round.
     * At the width these tests run nothing usually needs paging, which is deliberate — but a
     * toolbar that grows an action later should not quietly stop being tested.
     */
    public boolean offers(String label) {
        for (int page = 0; page <= TOOLBAR_PAGES; page++) {
            if (isOnScreen(label)) {
                return true;
            }
            if (!toggleToolbar()) {
                return false;
            }
        }
        return false;
    }

    private static final int TOOLBAR_PAGES = 4;

    private Locator button(String label) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label));
    }

    private boolean isOnScreen(String label) {
        var button = button(label);
        return button.count() > 0 && button.first().isVisible();
    }

    /**
     * Clicks the "⋯" expander. The character is U+22EF, the midline horizontal ellipsis, and not
     * the three middle dots the header's menu bar uses — they look identical and are not.
     */
    private boolean toggleToolbar() {
        var expander = button("\u22ef").first();
        if (expander.count() == 0 || !expander.isVisible()) {
            return false;
        }
        expander.click();
        page.waitForTimeout(300);
        return true;
    }

    /**
     * Clicks an operator action. The click only publishes a request — the pod that owns the
     * process carries it out and the view polls to catch up — so callers must assert on what the
     * badge becomes, never on what it is immediately afterwards.
     */
    public ProcessDetailPage runAction(String label) {
        action(label).click();
        return this;
    }

    /** Confirms the dialog that {@code @Action(confirmationRequired = true)} puts up. */
    public ProcessDetailPage confirm() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(java.util.regex.Pattern.compile("(Confirm|OK|Yes|Accept)"))).first().click();
        return this;
    }

    public ProcessesPage backToList() {
        page.getByText("Back to list").click();
        var processes = new ProcessesPage(page);
        processes.awaitLoaded();
        return processes;
    }
}
