package io.mateu.workflow.application.usecases.process.parentnotify;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Completes (or errors) the parent PROCESS step execution when a child process reaches a
 * terminal status. Must be called from every seam where a process is saved with a terminal
 * status (step-over, step-execution-driven updates, cancellation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotifyParentStepService {

    final StepExecutionRepository stepExecutionRepository;
    // ObjectProvider (not direct injection): UpdateStepExecutionUseCase sits on the same
    // step-update pipeline as the use cases that call this service, so a direct constructor
    // dependency could close an injection cycle.
    final ObjectProvider<UpdateStepExecutionUseCase> updateStepExecutionUseCase;

    public void processReachedTerminalStatus(Process process) {
        if (process.getParentStepExecutionId() == null) {
            return;
        }
        var parentStepExecution = stepExecutionRepository.findById(process.getParentStepExecutionId()).orElse(null);
        if (parentStepExecution == null) {
            log.warn("Child process {} references unknown parent step execution {}",
                    process.getId(), process.getParentStepExecutionId());
            return;
        }
        if (!StepExecutionStatus.PENDING.equals(parentStepExecution.getStatus())) {
            // Idempotency: the parent step already left its wait (earlier notification,
            // timeout, cancellation, …) — a late or redelivered notification must not
            // resurrect it.
            return;
        }
        var step = pojoFromJson(parentStepExecution.getStepJson(), Step.class);
        if (ProcessStatus.COMPLETED.equals(process.getStatus())) {
            var outputVariables = step.outputVariables() == null ? List.<String>of() : step.outputVariables();
            var variables = process.getVariables() == null ? List.<Variable>of()
                    : process.getVariables().stream()
                            .filter(variable -> outputVariables.contains(variable.name()))
                            .toList();
            updateStepExecutionUseCase.getObject().handle(new UpdateStepExecutionCommand(
                    process.getParentStepExecutionId(),
                    variables,
                    "Child process " + process.getId() + " completed",
                    StepExecutionStatus.COMPLETED));
        } else if (ProcessStatus.ERROR.equals(process.getStatus())
                || ProcessStatus.CANCELLED.equals(process.getStatus())) {
            updateStepExecutionUseCase.getObject().handle(new UpdateStepExecutionCommand(
                    process.getParentStepExecutionId(),
                    List.of(),
                    "Child process " + process.getId() + " ended " + process.getStatus(),
                    StepExecutionStatus.ERROR));
        }
    }
}
