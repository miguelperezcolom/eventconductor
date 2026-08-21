package io.mateu.workflow.uie2e;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * HARD-UI-01..02 — stored cross-site scripting, in a real browser.
 *
 * <p>This is the one claim in the whole hardening suite that nothing else can make. Every other
 * test says a hostile value is stored and returned exactly as it was sent, and that is the right
 * behaviour — escaping on the way in is how a value silently stops being the value that was sent.
 * But it means the payload is still a payload when it reaches the page, and whether it stays inert
 * there is a property of the rendering, not of the engine. A Java test cannot see it. Only a
 * browser can say whether {@code <img src=x onerror=...>} is four dozen characters of text or a
 * script that just ran with the operator's session.
 *
 * <p>The payloads are the ones that survive being inserted as markup. {@code <script>} is the
 * famous one and the least dangerous: a script element added through {@code innerHTML} does not
 * execute. The event-handler attributes do, which is why they are what is asserted here.
 *
 * <p>Each of them sets {@code window.__xssExecuted}, so the assertion is direct rather than
 * inferential: not "the page looks fine", but "nothing the payload asked for happened".
 *
 * <p><b>What is not here, and why.</b> Two more journeys were written and then removed, both for
 * the same reason: their payload never reached a rendering surface, so they asserted nothing while
 * passing. A payload as the business key is not drawn anywhere — this console's grid has no
 * business-key column and neither does the detail header — and a payload typed into the search box
 * matches nothing and is not echoed back. Each journey that remains carries a control proving its
 * payload is genuinely on screen, which is what caught the other two. A green XSS test that never
 * rendered the payload is worse than no test: it is a standing claim that the UI is safe, backed by
 * nothing.
 */
class StoredXssJourneyTest extends AbstractUiE2eTest {

    /** Payloads that execute when inserted as markup, which is what makes them worth testing. */
    private static final String IMG = "<img src=x onerror=\"window.__xssExecuted=true\">";
    private static final String SVG = "<svg onload=\"window.__xssExecuted=true\">";
    private static final String BREAKOUT = "\"><img src=x onerror=\"window.__xssExecuted=true\">";
    private static final String SCRIPT = "<script>window.__xssExecuted=true</script>";

    /**
     * Every payload above contains this, so finding it on the page is the one control these tests
     * cannot do without.
     *
     * <p>"Nothing executed" is satisfied just as well by a page that never rendered the payload at
     * all — a tab that did not open, a grid that did not load, a value the view dropped. That test
     * passes for ever and protects nothing. Asserting the marker is on screen as text is what makes
     * the rest of the assertions mean something.
     */
    private static final String PAYLOAD_MARKER = "Executed";

    /** True if any payload rendered on this page managed to run. */
    private boolean payloadExecuted() {
        return Boolean.TRUE.equals(page.evaluate("() => window.__xssExecuted === true"));
    }

    /**
     * How many elements on the page carry this text.
     *
     * <p>The {@code text=} engine, and neither of the two obvious alternatives. This UI is nested
     * open shadow roots four deep: {@code document.body.innerText} sees none of it, and
     * {@code getByText} resolves nothing for the grid's slotted cells. {@code text=} pierces open
     * shadow roots and matches on substring, which is what actually finds these — with the marker
     * kept to one short word, because a longer one spans however the cell broke the value up for
     * wrapping and then matches nothing at all.
     */
    private com.microsoft.playwright.Locator showing(String text) {
        return page.locator("text=" + text).first();
    }

    /**
     * HARD-UI-01. A process whose variables are payloads, read tab by tab. The variables are the
     * widest surface: free text from whoever started the process, shown on the detail page and
     * carried into the graph's hover cards.
     */
    @Test
    void aProcessCarryingScriptPayloadsInItsVariablesRendersThemInert() {
        var id = startProcess("ui-greeting", "xss-variables",
                new Variable("img", IMG),
                new Variable("svg", SVG),
                new Variable("breakout", BREAKOUT),
                new Variable("script", SCRIPT));

        var detail = ui.goToProcesses().open(id);
        // Variables last, so the tab the payloads are on is the one left open — every tab visited
        // on the way had its chance to execute one, and window.__xssExecuted would still be set.
        for (var tab : List.of("Steps", "Errors", "Diagram", "Variables")) {
            detail.openTab(tab);
        }

        // The control, and a retrying assertion rather than a count: the tab renders its content
        // asynchronously, so counting immediately after the click reads an empty page and passes a
        // test that has not looked at anything. Everything below it runs on a settled page because
        // this one waited.
        assertThat(showing(PAYLOAD_MARKER)).isVisible();
        org.assertj.core.api.Assertions.assertThat(payloadExecuted())
                .as("a variable rendered on the process detail must not be able to run as script")
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(page.locator("img[onerror]").count())
                .as("no element from the payload may exist in the document at all")
                .isZero();
        org.assertj.core.api.Assertions.assertThat(page.locator("svg[onload]").count()).isZero();
    }

    /**
     * HARD-UI-02. And it is shown, not swallowed. Inertness alone would also be satisfied by a UI
     * that dropped the value, which would be its own bug: an operator searching for the process
     * would never find it, and the page would be quietly lying about what the process carries.
     */
    @Test
    void thePayloadIsStillVisibleAsTheTextItIs() {
        var id = startProcess("ui-greeting", "xss-visible",
                new Variable("note", "<b>bold</b> & <i>italic</i>"));

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Variables");

        // Visible as the characters it is made of: markup in a variable is content, not structure.
        assertThat(page.locator("text=<b>bold</b>").first()).isVisible();
        org.assertj.core.api.Assertions.assertThat(page.locator("b").count())
                .as("and not as the elements it spells")
                .isZero();
    }

}
