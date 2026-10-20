package io.mateu.workflow.uie2e.support;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.List;

/**
 * The single worker every ACTION is dispatched to.
 *
 * <p>Behaviour is keyed on the step id and fixed rather than programmable: these tests are about
 * what an operator sees and does in the browser, so the engine underneath should behave the same
 * way every run. A step named {@code charge} fails, which is what gives the UI a process that
 * rolls back — a compensation drawn amber, an Errors tab with a reason in it, and a process that
 * ends COMPENSATED instead of COMPLETED.
 */
public class UiTestWorker implements EmbeddedTaskExecutor {

    /** The step whose failure drives the saga rollback the UI tests read. */
    public static final String FAILING_STEP = "charge";

    private final UpdateStepExecutionUseCase updateStepExecution;

    public UiTestWorker(UpdateStepExecutionUseCase updateStepExecution) {
        this.updateStepExecution = updateStepExecution;
    }

    @Override
    public void execute(TaskExecutionRequested request) {
        if (FAILING_STEP.equals(request.stepId())) {
            report(request, StepExecutionStatus.ERROR, "The card was declined");
        } else {
            report(request, StepExecutionStatus.COMPLETED, "Done: " + request.stepId());
        }
    }

    private void report(TaskExecutionRequested request, StepExecutionStatus status, String log) {
        updateStepExecution.handle(new UpdateStepExecutionCommand(
                request.taskExecutionId(), List.of(), log, status));
    }
}
