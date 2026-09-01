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
 *
 * <p><b>The graph readers report "nothing yet" rather than throwing.</b> They reach into the
 * {@code eventconductor-workflow-graph} shadow DOM, and a custom element is in the document before
 * it has attached its shadow root — so {@code el.shadowRoot} is null for a window whose width
 * depends on how busy the machine is. Every caller of these methods is inside an
 * {@code awaitUntil} polling for the graph to appear, and that loop retries a false condition but
 * propagates an exception: a null dereference there is a failed test rather than another poll.
 * That is precisely how it failed, twice, on branches that touched nothing near the graph —
 * {@code TypeError: Cannot read properties of null (reading 'querySelectorAll')}. Returning an
 * empty result lets the wait do its job, and an unrendered graph now ends as a timeout that says
 * which condition never came true.
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

    /**
     * Waits for the way back out of the detail, which is what tells a loaded detail apart from a
     * listing still mounted underneath.
     *
     * <p>Located by ACCESSIBLE NAME rather than by visible text. Mateu 3.0-alpha.307 renders it as
     * a chevron before the title instead of a labelled button — the label moved to aria-label and
     * title, so the control and its name are both still there and only {@code getByText} stopped
     * finding it. Keying on the name is the better locator anyway: it survives the control being
     * drawn as an icon, and it fails when the name is dropped, which is when a screen reader user
     * loses the button.
     */
    public void awaitLoaded() {
        assertThat(backControl()).isVisible();
    }

    /** The back affordance, however the renderer chose to draw it. */
    private com.microsoft.playwright.Locator backControl() {
        return page.getByLabel("Back to list").or(page.getByText("Back to list")).first();
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
     * The sequence number each node carries, by step id — the diagram's answer to "when did this
     * run", as opposed to the tick's "did it".
     *
     * <p>Read out of the shadow DOM in one evaluate rather than as locators, because the assertion
     * is about the pairing: a number on its own says nothing, and two locator queries would let the
     * numbers and the nodes come back in unrelated orders.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, String> stepOrderNumbers() {
        var raw = page.locator("eventconductor-workflow-graph").first().evaluate("""
                el => {
                    const out = {};
                    if (!el.shadowRoot) return out;   // see the class javadoc
                    el.shadowRoot.querySelectorAll('.node[data-node]').forEach(node => {
                        const badge = node.querySelector('.ov-order text');
                        if (badge) out[node.getAttribute('data-node')] = badge.textContent.trim();
                    });
                    return out;
                }""");
        var numbers = new java.util.LinkedHashMap<String, String>();
        ((java.util.Map<String, Object>) raw).forEach((k, v) -> numbers.put(k, String.valueOf(v)));
        return numbers;
    }

    /**
     * Every guard chip that lands on top of a node, as "chip ↔ node" pairs.
     *
     * <p>Boxes rather than pixels: a chip is a rounded rectangle and a node is a rectangle, so
     * their client rects answer the question exactly. Compared in viewport coordinates so the
     * graph's own pan and zoom are already applied — the reader's overlap is what counts, not the
     * one in the untransformed coordinate system.
     *
     * <p>A one-pixel touch is not an overlap anybody sees; the check allows a small tolerance so it
     * measures "hides something" rather than "shares a border".
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> guardChipsCoveringNodes() {
        var raw = page.locator("eventconductor-workflow-graph").first().evaluate("""
                el => {
                    const hits = [];
                    if (!el.shadowRoot) return hits;
                    const nodes = [...el.shadowRoot.querySelectorAll('.node[data-node]')];
                    el.shadowRoot.querySelectorAll('.guard .guard-chip:not(.guard-full) rect')
                        .forEach(chipRect => {
                            const chip = chipRect.getBoundingClientRect();
                            const guard = chipRect.closest('.guard');
                            nodes.forEach(node => {
                                const inner = node.querySelector('.node-inner') || node;
                                const box = inner.getBoundingClientRect();
                                const overlap = chip.left < box.right - 2 && chip.right > box.left + 2
                                    && chip.top < box.bottom - 2 && chip.bottom > box.top + 2;
                                if (overlap) {
                                    hits.push(guard.getAttribute('data-edge') + ' ↔ '
                                        + node.getAttribute('data-node'));
                                }
                            });
                        });
                    return hits;
                }""");
        return new java.util.ArrayList<>((java.util.List<String>) (java.util.List<?>) raw);
    }

    /** The full text a guard chip reveals under the pointer, for the first chip on the canvas. */
    public String firstGuardFullText() {
        return page.locator("eventconductor-workflow-graph").first().evaluate("""
                el => {
                    if (!el.shadowRoot) return null;
                    const full = el.shadowRoot.querySelector('.guard .guard-full text');
                    return full ? full.textContent.trim() : null;
                }""") instanceof String text ? text : null;
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
        backControl().click();
        var processes = new ProcessesPage(page);
        processes.awaitLoaded();
        return processes;
    }
}
