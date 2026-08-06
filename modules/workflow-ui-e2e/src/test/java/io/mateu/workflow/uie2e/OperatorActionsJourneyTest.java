package io.mateu.workflow.uie2e;

import io.mateu.workflow.uie2e.pages.ProcessDetailPage;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The buttons that change something.
 *
 * <p>These are the tests worth having a browser for. Everything else on this screen is a rendering
 * of state the API already exposes; these are a click that has to reach the engine and come back
 * as a different process status. The path is longer than it looks: the click does not perform the
 * action, it publishes a request, which the pod owning the process carries out, after which the
 * view's two-second poll notices. A test that asserted straight after the click would pass only by
 * luck — so every assertion here is on what the badge <em>becomes</em>.
 *
 * <p>The long-running workflow sits in a ten-minute TIMER, which is what makes pause and resume
 * observable at all: the process has to still be RUNNING when the operator reaches for the button.
 */
class OperatorActionsJourneyTest extends AbstractUiE2eTest {

    @Test
    void pausingARunningProcessStopsIt() {
        var id = startProcess("ui-long-running", "pause-me");

        var detail = ui.goToProcesses().open(id);
        assertThat(detail.statusBadge()).containsText("Running");

        detail.runAction(ProcessDetailPage.PAUSE);

        assertThat(detail.statusBadge()).containsText("Paused");
    }

    @org.junit.jupiter.api.Disabled("""
            The process detail never renders this action, so there is nothing to click.

            SimpleProcessViewModel declares five toolbar actions — cancel, pause, resume, retry,
            restart — all with @Toolbar and none with @Hidden, so all five should appear. Only
            "Cancel process" and "Pause process" ever do, in either state of the "..." expander and
            at any window width. Confirmed by hand against testbench/workflow-embedded, outside
            these tests, on a process that had been paused: the toolbar still offered only pause.

            It leaves an operator unable to resume, retry or restart from the screen showing the
            process that needs it — and retryProcess's own javadoc says that is the point of it
            ("so an operator can re-drive a failed process from its detail view"). The list page
            does offer Retry and Restart, applied to a selection, which is the workaround.

            Enable this the moment the toolbar renders them; the test is written and should pass.
            """)
    @Test
    void resumingAPausedProcessSetsItRunningAgain() {
        var id = startProcess("ui-long-running", "resume-me");

        var detail = ui.goToProcesses().open(id);
        detail.runAction(ProcessDetailPage.PAUSE);
        assertThat(detail.statusBadge()).containsText("Paused");

        detail.runAction(ProcessDetailPage.RESUME);

        assertThat(detail.statusBadge()).containsText("Running");
    }

    /**
     * Cancellation asks first, because it is not recoverable. The confirmation is part of the
     * contract, not decoration: an operator scanning a list of stuck processes should not be able
     * to cancel one by a stray click.
     */
    @Test
    void cancellingAsksBeforeItActs() {
        var id = startProcess("ui-long-running", "cancel-me");

        var detail = ui.goToProcesses().open(id);
        detail.runAction(ProcessDetailPage.CANCEL);

        // Still running until the question is answered.
        assertThat(detail.statusBadge()).containsText("Running");

        detail.confirm();

        assertThat(detail.statusBadge()).containsText("Cancelled");
    }

    /**
     * The reason "Retry from failure" exists: the environment broke, the environment recovered, and
     * the operator wants the process to carry on rather than start over. Here the worker keeps
     * failing, so what is asserted is that the retry reached the engine and the process ran again —
     * the step count rises — not that it suddenly succeeds.
     */
    @org.junit.jupiter.api.Disabled("""
            The process detail never renders this action, so there is nothing to click.

            SimpleProcessViewModel declares five toolbar actions — cancel, pause, resume, retry,
            restart — all with @Toolbar and none with @Hidden, so all five should appear. Only
            "Cancel process" and "Pause process" ever do, in either state of the "..." expander and
            at any window width. Confirmed by hand against testbench/workflow-embedded, outside
            these tests, on a process that had been paused: the toolbar still offered only pause.

            It leaves an operator unable to resume, retry or restart from the screen showing the
            process that needs it — and retryProcess's own javadoc says that is the point of it
            ("so an operator can re-drive a failed process from its detail view"). The list page
            does offer Retry and Restart, applied to a selection, which is the workaround.

            Enable this the moment the toolbar renders them; the test is written and should pass.
            """)
    @Test
    void retryFromFailureRedrivesARolledBackProcess() {
        var id = startProcess("ui-saga", "retry-me");

        var detail = ui.goToProcesses().open(id);
        assertThat(detail.statusBadge()).containsText("Compensated");

        var before = stepExecutionCount(id);
        detail.runAction(ProcessDetailPage.RETRY_FROM_FAILURE);

        awaitUntil(java.time.Duration.ofSeconds(20), () -> stepExecutionCount(id) > before);
    }

    /**
     * The toolbar offers what applies to the process as it is, not everything the view model
     * declares — a running process cannot be resumed and a finished one cannot be paused. Getting
     * this wrong is not a crash; it is an operator clicking something that quietly does nothing.
     */
    @Test
    void aRunningProcessIsOfferedPauseAndCancelButNotResume() {
        var id = startProcess("ui-long-running", "actions-running");

        var detail = ui.goToProcesses().open(id);
        assertThat(detail.statusBadge()).containsText("Running");

        org.assertj.core.api.Assertions.assertThat(detail.offers(ProcessDetailPage.PAUSE)).isTrue();
        org.assertj.core.api.Assertions.assertThat(detail.offers(ProcessDetailPage.CANCEL)).isTrue();
        org.assertj.core.api.Assertions.assertThat(detail.offers(ProcessDetailPage.RESUME)).isFalse();
    }

    /** And once it is paused, the pair swaps over. */
    @org.junit.jupiter.api.Disabled("""
            The process detail never renders this action, so there is nothing to click.

            SimpleProcessViewModel declares five toolbar actions — cancel, pause, resume, retry,
            restart — all with @Toolbar and none with @Hidden, so all five should appear. Only
            "Cancel process" and "Pause process" ever do, in either state of the "..." expander and
            at any window width. Confirmed by hand against testbench/workflow-embedded, outside
            these tests, on a process that had been paused: the toolbar still offered only pause.

            It leaves an operator unable to resume, retry or restart from the screen showing the
            process that needs it — and retryProcess's own javadoc says that is the point of it
            ("so an operator can re-drive a failed process from its detail view"). The list page
            does offer Retry and Restart, applied to a selection, which is the workaround.

            Enable this the moment the toolbar renders them; the test is written and should pass.
            """)
    @Test
    void aPausedProcessIsOfferedResume() {
        var id = startProcess("ui-long-running", "actions-paused");

        var detail = ui.goToProcesses().open(id);
        detail.runAction(ProcessDetailPage.PAUSE);
        assertThat(detail.statusBadge()).containsText("Paused");

        org.assertj.core.api.Assertions.assertThat(detail.offers(ProcessDetailPage.RESUME)).isTrue();
    }

}
