package io.mateu.workflow.application.usecases.process.retry;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * Picks a stopped process up where it stopped: the steps that failed run again, the ones that
 * already succeeded are left alone.
 *
 * <p>Works from ERROR — a step exhausted its retries — and from CANCELLED, where an operator (or
 * a parent process) stopped everything in flight and now wants it to carry on. In the cancelled
 * case the steps to revive are the cancelled ones, which is every step that had not finished; in
 * the failed case, only the ones that actually failed.
 *
 * <p>Refuses anything else. Nothing in flight is safe to re-drive from the outside, and a
 * COMPLETED or COMPENSATED process is finished by a definition the engine treats as sticky. The
 * check lives here rather than in the buttons because the list applies this to a whole selection,
 * and because the request also arrives from MCP and the REST API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryProcessUseCase {

    /** Statuses a process can be picked up from. */
    private static final Set<ProcessStatus> RESUMABLE =
            EnumSet.of(ProcessStatus.ERROR, ProcessStatus.CANCELLED);

    /** What "where it stopped" means for a process that failed. */
    private static final Set<StepExecutionStatus> FAILED =
            EnumSet.of(StepExecutionStatus.ERROR, StepExecutionStatus.TIMEOUT);

    /** …and for one that was cancelled: everything that had not finished by then. */
    private static final Set<StepExecutionStatus> FAILED_OR_CANCELLED =
            EnumSet.of(StepExecutionStatus.ERROR, StepExecutionStatus.TIMEOUT,
                    StepExecutionStatus.CANCELLED);

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final WorkflowMetrics workflowMetrics;

    public void handle(RetryProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (!RESUMABLE.contains(process.getStatus())) {
            log.warn("Process {} cannot be retried from status {} — ignoring",
                    process.getId(), process.getStatus());
            return;
        }

        var revivable = ProcessStatus.CANCELLED.equals(process.getStatus())
                ? FAILED_OR_CANCELLED : FAILED;

        var stepExecutions = stepExecutionRepository.findByProcess(process);
        boolean changed = false;
        for (var stepExecution : stepExecutions) {
            if (revivable.contains(stepExecution.getStatus())) {
                stepExecution.updateStatus(StepExecutionStatus.CREATED);
                stepExecutionRepository.save(stepExecution);
                workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                        WorkflowMetrics.RetryTrigger.MANUAL);
                changed = true;
            }
        }

        if (changed) {
            process = process.withStatus(ProcessStatus.RUNNING).withFinished(null);
            processRepository.save(process);
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
    }
}
