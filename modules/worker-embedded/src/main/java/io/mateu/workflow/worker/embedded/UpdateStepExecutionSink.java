package io.mateu.workflow.worker.embedded;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import java.util.List;

/**
 * Answers the in-process engine by calling {@link UpdateStepExecutionUseCase} directly — the
 * embedded counterpart of the Kafka worker's reply over the {@code upstream} topic. The step is
 * keyed by {@code taskExecutionId} (the engine's step-execution id), the reason rides the command's
 * log so a failure leaves a trace, and the worker-api {@code Variable}s are turned into the domain's.
 *
 * <p>Wrapped by the runtime's transaction-aware sink, so when the engine runs the task inside a
 * transaction the callback lands in {@code afterCommit}; in memory mode there is no transaction and
 * the call is synchronous and reentrant, exactly as a hand-written embedded worker would be.
 */
final class UpdateStepExecutionSink implements io.mateu.workflow.worker.api.TaskReplySink {

    private final UpdateStepExecutionUseCase updateStepExecution;

    UpdateStepExecutionSink(UpdateStepExecutionUseCase updateStepExecution) {
        this.updateStepExecution = updateStepExecution;
    }

    @Override
    public void running(TaskExecutionRequested task) {
        updateStepExecution.handle(new UpdateStepExecutionCommand(
                task.taskExecutionId(), List.of(), "", StepExecutionStatus.RUNNING));
    }

    @Override
    public void completed(TaskExecutionRequested task, List<io.mateu.workflow.dtos.Variable> variables) {
        updateStepExecution.handle(new UpdateStepExecutionCommand(
                task.taskExecutionId(), toDomain(variables), "", StepExecutionStatus.COMPLETED));
    }

    @Override
    public void failed(TaskExecutionRequested task, List<io.mateu.workflow.dtos.Variable> variables,
                       String reason) {
        updateStepExecution.handle(new UpdateStepExecutionCommand(
                task.taskExecutionId(), toDomain(variables), reason == null ? "" : reason,
                StepExecutionStatus.ERROR));
    }

    private static List<Variable> toDomain(List<io.mateu.workflow.dtos.Variable> variables) {
        return variables.stream().map(v -> new Variable(v.name(), v.value())).toList();
    }
}
