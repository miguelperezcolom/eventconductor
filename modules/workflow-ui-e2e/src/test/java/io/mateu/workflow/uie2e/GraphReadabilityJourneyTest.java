package io.mateu.workflow.uie2e;

import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the diagram has to say without being asked: what ran when, and every condition on it.
 *
 * <p>Both of these are about a graph being read rather than a graph being correct. The nodes and
 * the lines can all be right and the picture still fail its reader — a number missing where the
 * order is the question, a condition drawn on top of the step it applies to.
 */
class GraphReadabilityJourneyTest extends AbstractUiE2eTest {

    private static final Duration THE_GRAPH_LAYS_OUT = Duration.ofSeconds(10);

    /**
     * The shape of a workflow does not say what order a run took. Two branches drawn side by side
     * ran in some order; a step drawn between two others may have run before both. The tick says a
     * step ran, and this says when.
     */
    @Test
    void theDiagramNumbersTheStepsInTheOrderTheyRan() {
        var id = startProcess("ui-greeting", "graph-order");

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Diagram");

        awaitUntil(THE_GRAPH_LAYS_OUT, () -> detail.stepOrderNumbers().size() >= 3);

        // ui-greeting is start → greet → end, and a finished process ran them in that order.
        assertThat(detail.stepOrderNumbers())
                .as("every step that ran carries its place in the run")
                .containsEntry("start", "1")
                .containsEntry("greet", "2")
                .containsEntry("end", "3");
    }

    /**
     * A guard chip is drawn over the lines, which is right — it belongs to the way in, not to the
     * step. The trouble is that an expression is as long as its author needed it to be, and a chip
     * drawn in full can be wider than the nodes it sits between, hiding the very step it is a
     * condition for.
     */
    @Test
    void noGuardChipIsDrawnOnTopOfAStep() {
        var id = startProcess("ui-guards", "graph-guards");

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Diagram");

        awaitUntil(THE_GRAPH_LAYS_OUT, () -> !detail.stepOrderNumbers().isEmpty());

        assertThat(detail.guardChipsCoveringNodes())
                .as("a condition must not cover the step it is a condition for")
                .isEmpty();
    }

    /**
     * And the shortening cannot be the end of it: a condition nobody can read in full is a
     * condition nobody can check. The whole expression is one hover away, and this asserts it is
     * really there — the same text, not the truncation drawn twice.
     */
    @Test
    void theWholeExpressionIsOneHoverAway() {
        var id = startProcess("ui-guards", "graph-guards-hover");

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Diagram");

        awaitUntil(THE_GRAPH_LAYS_OUT, () -> detail.firstGuardFullText() != null);

        assertThat(detail.firstGuardFullText())
                .as("the expansion carries the expression as written, not the shortened form")
                .contains("customerTier")
                .doesNotContain("…");
    }
}
