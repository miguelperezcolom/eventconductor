package io.mateu.workflow.uie2e;

import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI-LIVE — the process page's diagram follows the process while the page is open.
 *
 * <p>The detail view polls every two seconds, and the point of that poll is a page an operator can
 * leave open and trust. Everything on it is a value — the badge, the tabs, the tables — except the
 * one thing with no equivalent anywhere else: the diagram is a custom element, and an element's
 * data lives in its <b>attributes</b>. Mateu's {@code State} update carries values and not
 * component metadata, so a refresh that returns a {@code State} leaves the {@code overlay}
 * attribute exactly as it was on first render. The process runs to completion behind a picture
 * that never moves, for the life of the tab.
 *
 * <p><b>This test asserts the attribute, not the picture</b>, and that is the whole design. The
 * rendered SVG looks fine either way: every node is still there, drawn in the state it had when
 * the tab was opened. A test that read the drawing would pass while the bug was present, which is
 * how this went unnoticed — {@code ProcessDetailJourneyTest} already draws the diagram, and draws
 * it once.
 *
 * <p>It changes the process from <em>outside</em> the browser rather than by clicking something,
 * because that is the case the poll exists for: an operator watching a process that somebody else,
 * or the engine itself, is moving.
 */
class DiagramStaysLiveJourneyTest extends AbstractUiE2eTest {

    /** Comfortably past the view's two-second poll, without turning a failure into a long wait. */
    private static final Duration A_FEW_POLLS = Duration.ofSeconds(15);

    @org.junit.jupiter.api.Disabled("""
            The diagram does not follow the process, and the fix for it still costs the status badge.

            This test fails on main, deterministically: the overlay attribute the graph is drawn
            from is never resent by a State update, so the picture an operator is watching is the
            one the tab opened with. Verified both ways — deliberately breaking the refresh so it
            never reloads makes this test fail the same way, so it is measuring what it claims to.

            Returning the view model from refresh() instead of a State of it fixes the diagram: the
            element's attributes are rebuilt on every poll and this test passes in 7 seconds. But the
            view then re-renders itself, and the status badge lives in the CRUD chrome around it
            (mateu-content-header), not in the view — so the badge stops updating instead, and
            OperatorActionsJourneyTest.pausingARunningProcessStopsIt and .cancellingAsksBeforeItActs
            fail on exactly that.

            Re-checked on 3.0-alpha.294, both ways, after the two defects reported upstream were
            said to be fixed in it. One of them was: the null-Integer hang on the task-override
            create form is gone, and NewOverrideFormJourneyTest is enabled because of it. This one
            is not — the trade is exactly where it was on 291 and 293:

                State(loaded)  → diagram frozen, badge correct   (6 of 6 operator tests pass)
                loaded         → diagram live, badge dead        (2 of 6 operator tests fail)

            So it is one bug or the other until mateu lets an Element's data travel in a State
            update, or lets a view re-render without discarding the state its surroundings put
            there (mateu#314). The badge is what an operator reads to decide; the diagram is what
            they read to understand. Trading the first for the second is not obviously an
            improvement, which is why this is parked rather than merged.

            Enable this the moment refresh() can rebuild the element without costing the chrome;
            the test is written and passes against that fix today.
            """)
    @Test
    void theDiagramFollowsTheProcessWhilePageIsOpen() {
        // A process that sits still on a ten-minute TIMER, so the page is opened on a diagram that
        // has something left to change.
        var id = startProcess("ui-long-running", "diagram-live");

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Diagram");

        var onOpening = detail.diagramOverlay();
        assertThat(onOpening)
                .as("the diagram is drawn with the state the process is in when the tab opens")
                .isNotNull()
                .contains("wait");
        assertThat(onOpening)
                .as("nothing is cancelled yet")
                .doesNotContain("CANCELLED");

        // Somebody else cancels it — an operator on another screen, an MCP tool, a caller with the
        // business key. Nothing touches this browser.
        processUpstreamEvents.handle(new ProcessUpstreamEventCommand(
                new ProcessCancellationRequested("diagram-live", id)));

        awaitUntil(A_FEW_POLLS, () -> {
            var now = detail.diagramOverlay();
            return now != null && now.contains("CANCELLED");
        });

        // And the overlay is genuinely new, not the same string with a cancelled step appended by
        // some other means: the state the page opened with is gone.
        assertThat(detail.diagramOverlay())
                .as("the poll rebuilt the element's attributes")
                .isNotEqualTo(onOpening);
    }
}
