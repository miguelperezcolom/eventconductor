package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.domain.aggregates.StepExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overlay carrying processes that are stopped at a step, not just live on one.
 *
 * <p>The gap this closes: the overlay is built from {@code findPendingOrRunning}, so a process that
 * is RUNNING with no live step execution has no step in it and therefore appears on no node at all.
 * That is exactly the process worth looking at — one whose branch no guard matched — and the
 * picture was silent about it.
 */
class WorkflowGraphOverlayStoppedTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> overlay, String stepId) {
        return (Map<String, Object>) overlay.get(stepId);
    }

    @Test
    @DisplayName("a step with only stopped processes still gets a node entry")
    void reportsAStepThatHasNoLiveExecutions() {
        var overlay = WorkflowGraphOverlays.overlay(List.<StepExecution>of(), Map.of("decide", 3));

        assertThat(overlay).containsOnlyKeys("decide");
        assertThat(entry(overlay, "decide")).containsEntry("stopped", 3);
        // No live executions, so no live count at all rather than a zero: the badge should say
        // "three stopped here", not "none running, three stopped".
        assertThat(entry(overlay, "decide")).doesNotContainKey("count");
    }

    /**
     * Kept apart rather than summed. A live step is a worker owing an answer; a stopped one is a
     * process that is not going anywhere, and the operator's next move differs. One number covering
     * both would read as throughput when half of it is a stall.
     */
    @Test
    @DisplayName("live and stopped are separate numbers on the same step")
    void keepsTheTwoCountsApart() {
        var live = List.of(stepExecution("decide"), stepExecution("decide"));
        var overlay = WorkflowGraphOverlays.overlay(live, Map.of("decide", 1));

        assertThat(entry(overlay, "decide")).containsEntry("count", 2);
        assertThat(entry(overlay, "decide")).containsEntry("stopped", 1);
    }

    @Test
    @DisplayName("nothing live and nothing stopped is still no overlay at all")
    void staysEmptyWhenThereIsNothingToSay() {
        assertThat(WorkflowGraphOverlays.overlay(List.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("the old single-argument form still means live only")
    void theLiveOnlyFormIsUnchanged() {
        var overlay = WorkflowGraphOverlays.overlay(List.of(stepExecution("decide")));

        assertThat(entry(overlay, "decide")).containsEntry("count", 1);
        assertThat(entry(overlay, "decide")).doesNotContainKey("stopped");
    }

    /**
     * The histogram is built from live steps' start times, so a step that only holds stopped
     * processes has none of its own. It gets an empty array rather than no key, so the viewer's
     * day slider has something to sum either way.
     */
    @Test
    @DisplayName("a stopped-only step carries an empty heat histogram, not a missing one")
    void carriesAnEmptyHistogramRatherThanNone() {
        var overlay = WorkflowGraphOverlays.overlay(List.of(), Map.of("decide", 1));

        assertThat(entry(overlay, "decide")).containsKey("heat");
        assertThat((int[]) entry(overlay, "decide").get("heat"))
                .hasSize(WorkflowGraphOverlays.HEAT_WINDOW_DAYS)
                .containsOnly(0);
    }

    private static StepExecution stepExecution(String stepId) {
        return StepExecution.builder()
                .stepId(stepId)
                .startedAt(java.time.LocalDateTime.now())
                .build();
    }
}
