package io.mateu.workflow.application.usecases.process.rollback;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.StepExecutions;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rolls a running process back because the engine cut it short from outside: its parent cancelled the
 * PROCESS step that started it — the parent is rolling back, its forEach failed fast, or its flow
 * abandoned the branch ({@code compensateLosers} on an XOR join, {@code compensateAbandoned} on an END).
 *
 * <p>Unlike {@code CancelProcessUseCase} (what an operator's cancel does, and still does: no rollback),
 * the work the process has already done is undone:
 *
 * <ul>
 *   <li>the process is marked failed ({@code rollbackReason}, status ERROR), which holds its flow and
 *       starts its saga rollback — the same machinery a failed step starts, so its completed
 *       compensable steps are compensated newest first, and a step that completes after being
 *       cancelled here is compensated too;</li>
 *   <li>what it has in flight is cancelled (the worker is told, as on any cancel); a cancelled PROCESS
 *       step rolls its own child back in turn, which is how grandchildren follow;</li>
 *   <li>the rollback ends COMPENSATED (COMPENSATION_FAILED if a compensation fails), also when there
 *       was nothing to undo, and the parent's own rollback waits for that.</li>
 * </ul>
 *
 * <p>A process that already finished is left alone: a COMPLETED child's work is undone by its parent
 * step's compensation (it reports back as a completed run), and one already ERROR is rolling back (or
 * failed) on its own — it is only marked, so that it ends COMPENSATED rather than staying ERROR.
 * Idempotent: asked again, it runs the cascade again, which only reaches children still running.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RollBackProcessUseCase {

    private static final Set<ProcessStatus> FINISHED = EnumSet.of(ProcessStatus.COMPLETED, ProcessStatus.CANCELLED,
            ProcessStatus.COMPENSATED, ProcessStatus.COMPENSATION_FAILED);

    /** What is cancelled when the rollback starts: whatever is waiting on a worker or its retry. */
    private static final Set<StepExecutionStatus> CANCELLED_ON_ROLLBACK = EnumSet.of(StepExecutionStatus.PENDING,
            StepExecutionStatus.RUNNING, StepExecutionStatus.AWAITING_RETRY);

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final DownstreamEventPublisher downstreamEventPublisher;
    final ProcessLockService processLockService;
    // A provider: the cascade into grandchildren comes back here through it.
    final ObjectProvider<CancelChildProcessService> cancelChildProcessService;

    /** @param reason why, for the process and its steps' logs ("its parent rolled back", …) */
    public void handle(String processId, String reason) {
        var cancelled = new AtomicReference<List<StepExecution>>();
        ProcessLockService.runExclusivelyOrThrow(processLockService, processId,
                () -> cancelled.set(rollBackUnderLock(processId, reason)));
        var steps = cancelled.get();
        if (steps == null) {
            return;
        }
        // After the lock, as CancelProcessUseCase does: the cascade takes the children's locks.
        for (var step : steps) {
            cancelChildProcessService.getObject().rollBackChildrenOf(step, "its parent " + processId + " rolled back");
        }
    }

    private List<StepExecution> rollBackUnderLock(String processId, String reason) {
        var process = processRepository.findById(processId).orElse(null);
        if (process == null || FINISHED.contains(process.getStatus())) {
            return null;
        }
        var executions = stepExecutionRepository.findByProcessId(processId);
        if (process.isRollbackRequested()) {
            // Asked again (a redelivery): the cascade is idempotent, so it simply runs again.
            return executions.stream().filter(e -> e.getStatus() == StepExecutionStatus.CANCELLED).toList();
        }
        boolean alreadyFailed = process.getStatus() == ProcessStatus.ERROR;
        processRepository.save(process.withRollbackReason(reason).withStatus(ProcessStatus.ERROR));
        log.info("Process {} is rolled back: {}", processId, reason);

        var cancelled = new ArrayList<StepExecution>();
        if (!alreadyFailed) {
            // Decided up front, before anything is saved: each save announces a status change, and the
            // rollback that starts from it may already be running a compensation by the time a later
            // step of this loop is looked at (in memory mode the very same instances move in place).
            var compensationStepIds = compensationStepIdsOf(executions);
            var toCancel = new ArrayList<StepExecution>();
            var quietly = new java.util.HashSet<String>();
            for (var execution : executions) {
                if (execution.isIteration() || !CANCELLED_ON_ROLLBACK.contains(execution.getStatus())
                        || execution.getInvocationId() != null
                        || compensationStepIds.contains(execution.getStepId())
                        || execution.isCleanupCompensation() || execution.compensatedStepId() != null) {
                    // Iterations go with their container; an agent's invocation is left to finish (its
                    // effects must reach a known outcome first) and is cancelled when the rollback ends;
                    // a compensation is the rollback itself.
                    continue;
                }
                if (execution.isContainer()) {
                    for (var iteration : StepExecutions.iterationsOf(execution, executions)) {
                        if (CANCELLED_ON_ROLLBACK.contains(iteration.getStatus())) {
                            toCancel.add(iteration);
                            quietly.add(iteration.getId());
                        }
                    }
                }
                toCancel.add(execution);
            }
            for (var execution : toCancel) {
                cancel(execution, reason, quietly.contains(execution.getId()));
                cancelled.add(execution);
            }
        }
        if (cancelled.isEmpty() && !executions.isEmpty()) {
            // Nothing was cancelled, so no status change announces the process: wake its handler, which
            // starts the rollback (or ends it at once when there is nothing to undo).
            var any = executions.get(0);
            any.announceStatusAgain();
            stepExecutionRepository.save(any);
        }
        return cancelled;
    }

    /** The steps that compensate another: they are the rollback, never cut short by it. */
    private static Set<String> compensationStepIdsOf(List<StepExecution> executions) {
        var ids = new java.util.HashSet<String>();
        for (var execution : executions) {
            if (execution.getStepJson() == null) {
                continue;
            }
            try {
                var step = io.mateu.core.infra.JsonSerializer.pojoFromJson(execution.getStepJson(),
                        io.mateu.workflow.domain.aggregates.Step.class);
                if (step.compensationStepId() != null && !step.compensationStepId().isBlank()) {
                    ids.add(step.compensationStepId());
                }
            } catch (RuntimeException e) {
                // An unreadable step names no compensation.
            }
        }
        return ids;
    }

    private void cancel(StepExecution execution, String reason, boolean quietly) {
        if (execution.getStatus().isInFlightAtAWorker()) {
            downstreamEventPublisher.publish(new TaskCancellationRequested(execution.getId(), execution.getInvocationId()),
                    execution.topic());
        }
        execution.logInfo("Step " + execution.getStepId() + " cancelled: its process is rolled back (" + reason + ").");
        if (quietly) {
            execution.cancelQuietly();
        } else {
            execution.updateStatus(StepExecutionStatus.CANCELLED);
        }
        stepExecutionRepository.save(execution);
    }
}
