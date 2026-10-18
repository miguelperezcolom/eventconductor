package io.mateu.workflow.application.usecases.stepexecution.retry;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryStepExecutionUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final WorkflowMetrics workflowMetrics;
    final ProcessLockService processLockService;

    public void handle(RetryStepExecutionCommand command) {
        var processId = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow()
                .getProcessId();
        // Under the process lock, like every other writer of the process: the step is re-read
        // inside it, so the status check, the revival and the step-over are one unit and cannot
        // interleave with the relay moving the same process. A lock that cannot be had fails
        // rather than doing nothing silently — the requested event is redelivered.
        ProcessLockService.runExclusivelyOrThrow(processLockService, processId, () -> doHandle(command));
    }

    private void doHandle(RetryStepExecutionCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

        if (!StepExecutionStatus.ERROR.equals(stepExecution.getStatus())
                && !StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus())) {
            return;
        }

        var process = processRepository.findById(stepExecution.getProcessId()).orElseThrow();
        if (ProcessStatus.CANCELLED.equals(process.getStatus())) {
            // Retrying a step must not revive a cancelled process (its other steps
            // are already CANCELLED and will never run).
            return;
        }

        stepExecution.updateStatus(StepExecutionStatus.CREATED);
        stepExecutionRepository.save(stepExecution);
        workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                WorkflowMetrics.RetryTrigger.MANUAL);

        processRepository.save(process.withStatus(ProcessStatus.RUNNING));
        stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
    }
}
