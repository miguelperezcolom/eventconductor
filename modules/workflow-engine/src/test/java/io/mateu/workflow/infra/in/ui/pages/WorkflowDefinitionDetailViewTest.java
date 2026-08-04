package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDefinitionDetailViewTest {

    /** A live step execution carrying its definition + step, as {@code findPendingOrRunning} returns. */
    private StepExecution se(String definitionId, String stepId) {
        var se = mock(StepExecution.class);
        when(se.getWorkflowDefinitionId()).thenReturn(definitionId);
        when(se.getStepId()).thenReturn(stepId);
        return se;
    }

    /** Same, but with a start time so it lands in a specific heatmap day-bucket. */
    private StepExecution se(String definitionId, String stepId, java.time.LocalDateTime startedAt) {
        var se = se(definitionId, stepId);
        when(se.getStartedAt()).thenReturn(startedAt);
        return se;
    }

    /** Only the step-execution repository is exercised; the rest go unused. */
    private WorkflowDefinitionDetailView view(StepExecutionRepository stepExecutions) {
        return new WorkflowDefinitionDetailView(null, stepExecutions, null, null, null, null, null);
    }

    @Test
    void countsLiveStepsOfThisDefinitionKeyedByStep() {
        var stepExecutions = mock(StepExecutionRepository.class);
        // One indexed query returns the whole system's live (PENDING/RUNNING) steps; the view filters
        // to this definition. Two live processes sit on "charge"; another definition is ignored.
        // Build the mocks first — nesting mock stubbing inside a when() confuses Mockito.
        var live = List.of(
                se("wd-1", "charge"),
                se("wd-1", "charge"),
                se("wd-2", "charge")); // excluded: other definition
        when(stepExecutions.findPendingOrRunning()).thenReturn(live);

        var counts = view(stepExecutions).liveProcessCountsByStep("wd-1");

        assertThat(counts).containsEntry("charge", 2);
        assertThat(counts).doesNotContainKey("start");
    }

    @Test
    void isEmptyWhenNoLiveStepSitsOnThisDefinition() {
        var stepExecutions = mock(StepExecutionRepository.class);
        var other = List.of(se("wd-2", "charge"));
        when(stepExecutions.findPendingOrRunning()).thenReturn(other);

        assertThat(view(stepExecutions).liveProcessCountsByStep("wd-1")).isEmpty();
    }

    @Test
    void bucketsStoppedTasksByDaysAgoAndFoldsThisDefinitionOnly() {
        var stepExecutions = mock(StepExecutionRepository.class);
        var now = java.time.LocalDateTime.now();
        var live = List.of(
                se("wd-1", "charge", now),                 // today   -> bucket 0
                se("wd-1", "charge", now.minusDays(5)),    // 5d ago  -> bucket 5
                se("wd-1", "charge", now.minusDays(1000)), // ancient -> folded into last bucket
                se("wd-2", "charge", now));                // excluded: other definition
        when(stepExecutions.findPendingOrRunning()).thenReturn(live);

        var heat = view(stepExecutions).stoppedTaskHeatByStep("wd-1");

        assertThat(heat).containsOnlyKeys("charge");
        var buckets = heat.get("charge");
        assertThat(buckets[0]).isEqualTo(1);
        assertThat(buckets[5]).isEqualTo(1);
        assertThat(buckets[WorkflowDefinitionDetailView.HEAT_WINDOW_DAYS - 1]).isEqualTo(1);
        // Summed over the whole window, the histogram reproduces this step's live count.
        assertThat(java.util.Arrays.stream(buckets).sum()).isEqualTo(3);
    }
}
