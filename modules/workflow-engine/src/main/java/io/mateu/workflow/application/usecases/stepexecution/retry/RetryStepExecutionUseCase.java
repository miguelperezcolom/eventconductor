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
@lombok.extern.slf4j.Slf4j
public class RetryStepExecutionUseCase {

    private static final java.util.Set<ProcessStatus> NOT_REVIVABLE = java.util.EnumSet.of(
            ProcessStatus.CANCELLED, ProcessStatus.COMPLETED,
            ProcessStatus.COMPENSATED, ProcessStatus.COMPENSATION_FAILED);

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
        if (NOT_REVIVABLE.contains(process.getStatus())) {
            // Retrying a step must not revive a finished process: a cancelled one's other steps
            // are already CANCELLED and will never run, a rolled-back saga (COMPENSATED, or
            // COMPENSATION_FAILED half way) already undid what this step would build on, and a
            // COMPLETED one is over. Same refusal as RetryProcessUseCase for the last three.
            log.warn("Step execution {} of process {} cannot be retried while the process is {} — ignoring",
                    stepExecution.id(), process.getId(), process.getStatus());
            return;
        }

        stepExecution.updateStatus(StepExecutionStatus.CREATED);
        stepExecutionRepository.save(stepExecution);
        workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                WorkflowMetrics.RetryTrigger.MANUAL);

        if (!ProcessStatus.PAUSED.equals(process.getStatus())) {
            // Reopened, like RetryProcessUseCase/RestartProcessUseCase: finished goes back to null,
            // which is what the analytics read model keys the retraction of the ERROR fact on
            // (CountedProcessRepository.reopened) and what the duration metrics measure up to.
            // A paused process stays paused: the revived step waits for the resume.
            processRepository.save(process.withStatus(ProcessStatus.RUNNING).withFinished(null));
        }
        stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
    }
}
