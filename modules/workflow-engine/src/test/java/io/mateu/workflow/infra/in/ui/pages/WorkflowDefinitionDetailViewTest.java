package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDefinitionDetailViewTest {

    private StepExecution se(String stepId, StepExecutionStatus status) {
        var se = mock(StepExecution.class);
        when(se.getStepId()).thenReturn(stepId);
        when(se.getStatus()).thenReturn(status);
        return se;
    }

    private Process process(String definitionId, ProcessStatus status) {
        var p = mock(Process.class);
        when(p.getWorkflowDefinitionId()).thenReturn(definitionId);
        when(p.getStatus()).thenReturn(status);
        return p;
    }

    /** Only the process + step-execution repositories are exercised; the rest go unused. */
    private WorkflowDefinitionDetailView view(ProcessRepository processes, StepExecutionRepository stepExecutions) {
        return new WorkflowDefinitionDetailView(null, processes, stepExecutions,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void countsRunningAndPendingStepsOfLiveProcessesForThisDefinition() {
        var processes = mock(ProcessRepository.class);
        var stepExecutions = mock(StepExecutionRepository.class);

        var live = process("wd-1", ProcessStatus.RUNNING);
        var alsoLive = process("wd-1", ProcessStatus.PENDING);
        var finished = process("wd-1", ProcessStatus.COMPLETED); // excluded: terminal status
        var otherDef = process("wd-2", ProcessStatus.RUNNING);   // excluded: other definition
        // Build the step-execution mocks first — nesting mock stubbing inside a when() confuses Mockito.
        var liveSteps = List.of(
                se("start", StepExecutionStatus.COMPLETED),      // not "at" this step
                se("charge", StepExecutionStatus.RUNNING));
        var alsoLiveSteps = List.of(se("charge", StepExecutionStatus.PENDING));

        when(processes.findAll()).thenReturn(List.of(live, alsoLive, finished, otherDef));
        when(stepExecutions.findByProcess(live)).thenReturn(liveSteps);
        when(stepExecutions.findByProcess(alsoLive)).thenReturn(alsoLiveSteps);

        var counts = view(processes, stepExecutions).liveProcessCountsByStep("wd-1");

        assertThat(counts).containsEntry("charge", 2);
        assertThat(counts).doesNotContainKey("start");
    }

    @Test
    void isEmptyWhenNoLiveProcessSitsOnThisDefinition() {
        var processes = mock(ProcessRepository.class);
        var stepExecutions = mock(StepExecutionRepository.class);
        var otherDef = process("wd-2", ProcessStatus.RUNNING);
        when(processes.findAll()).thenReturn(List.of(otherDef));

        assertThat(view(processes, stepExecutions).liveProcessCountsByStep("wd-1")).isEmpty();
    }
}
