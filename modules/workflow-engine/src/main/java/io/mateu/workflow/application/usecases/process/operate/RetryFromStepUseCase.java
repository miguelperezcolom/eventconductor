package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.services.LockKeyResolver;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a live process again from one of its steps — the reset of Temporal, the "move token" of an
 * instance modification: the step and every step downstream of it go back to the state they were
 * created in, and the flow runs from there. Upstream steps, and parallel branches that do not depend
 * on it, are left exactly as they are.
 *
 * <h2>What is reopened</h2>
 *
 * <p>The step and, transitively, every step that depends on it: whose preconditions name it (a link,
 * a JOIN's branch, a CHOICE's outgoing edge), or that is its on-timeout route — see
 * {@link ProcessOperationRules#downstreamOf}. A JOIN downstream waits again for the reopened branch
 * while its other branches stay completed; a CHOICE downstream decides again, from the variables as
 * they are now (its latch is released with its branches). Each reopened step gets a new dispatch
 * number, so a worker still answering for the abandoned attempt is dropped, not taken for the re-run's
 * result; a step still at a worker is told to stop, and a PROCESS step's running child is cancelled.
 *
 * <h2>When it is refused</h2>
 *
 * <p>See {@link ProcessOperationRules#planRetryFrom}: a process that is not RUNNING, PAUSED or ERROR;
 * a failed saga whose rollback is under way (once rolled back it is COMPENSATED or
 * COMPENSATION_FAILED, which are finished); a step that has not run yet, or is a compensation; another
 * failure the retry would not reopen; a step to reopen that is queued for a lock.
 *
 * <h2>The process afterwards</h2>
 *
 * <p>A failed process goes back to RUNNING, with {@code finished} cleared — which is what retracts its
 * failure from the analytics read model, as a retry or a restart does. A PAUSED one stays paused, the
 * reopened steps waiting for the resume.
 *
 * <h2>Locks</h2>
 *
 * <p>The definition's {@code processLock} is taken again the way a restart takes it: a failed process
 * gave it up when it failed, and the step-over gate re-acquires it (or queues the process) as soon as
 * the process is RUNNING. A step-level critical section is different, because the LOCK step that
 * opened it is upstream and is not re-run: when the reopened steps include work inside a section the
 * process no longer holds (its UNLOCK is reopened too, or there is none), the lock is taken again
 * here, without queueing. If someone else holds it by now, the retry point moves back to the section's
 * LOCK step, which re-runs and queues for the lock like any LOCK — the re-run never enters a critical
 * section without its lock.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryFromStepUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessLockService processLockService;
    final DownstreamEventPublisher downstreamEventPublisher;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final LockService lockService;
    final WorkflowMetrics workflowMetrics;
    final ProcessOperationRules rules;
    final OperatorAudit audit;
    final ObjectProvider<CancelProcessUseCase> cancelProcessUseCase;

    public void handle(RetryFromStepCommand command) {
        var childrenOf = new AtomicReference<List<String>>(List.of());
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(),
                () -> childrenOf.set(doHandle(command)));
        // After the lock, as the cancel cascade does: the children take their own locks.
        for (var stepExecutionId : childrenOf.get()) {
            ChildProcesses.of(processRepository, stepExecutionId).stream()
                    .filter(child -> !ChildProcesses.isFinished(child))
                    .forEach(child -> cancelProcessUseCase.getObject().handle(new CancelProcessCommand(child.getId())));
        }
    }

    /** @return the PROCESS step executions whose running children must now be cancelled */
    private List<String> doHandle(RetryFromStepCommand command) {
        var process = processRepository.findById(command.processId()).orElse(null);
        if (process == null) {
            log.warn("Retry from step: no process {} — ignoring", command.processId());
            return List.of();
        }
        var steps = stepExecutionRepository.findByProcess(process);
        ProcessOperationRules.RetryPlan plan;
        try {
            plan = rules.planRetryFrom(process, command.stepId(), steps);
        } catch (ProcessOperationRules.Refused refused) {
            audit.refused(process.getId(), null, OperatorAudit.RETRY_FROM_STEP, command.requestedBy(), command.reason(),
                    "retry from step " + command.stepId() + " of a " + process.getStatus() + " process: "
                            + refused.getMessage());
            return List.of();
        }

        var reacquired = new ArrayList<String>();
        var movedBackTo = new ArrayList<String>();
        plan = withCriticalSections(process, plan, steps, reacquired, movedBackTo);

        var reopened = new ArrayList<String>();
        var children = new ArrayList<String>();
        for (var stepExecution : plan.executions()) {
            if (isFresh(stepExecution)) {
                continue;
            }
            if (stepExecution.getStatus().isInFlightAtAWorker()) {
                downstreamEventPublisher.publish(new TaskCancellationRequested(stepExecution.getId()),
                        stepExecution.topic());
                var step = ProcessOperationRules.stepOf(stepExecution);
                if (step != null && StepType.PROCESS.equals(step.type())) {
                    children.add(stepExecution.getId());
                }
            }
            reopened.add(stepExecution.getStepId() + " (was " + stepExecution.getStatus() + ")");
            stepExecution.resetForRerun("Step " + stepExecution.getStepId() + " reset to run again from step "
                    + command.stepId());
            stepExecutionRepository.save(stepExecution);
        }

        var reopenedProcess = ProcessStatus.ERROR.equals(process.getStatus())
                ? process.withStatus(ProcessStatus.RUNNING).withFinished(null)
                : process;
        reopenedProcess = reopenedProcess.withCompletionPercentage(completion(steps));
        processRepository.save(reopenedProcess);
        workflowMetrics.retryPerformed(process.getWorkflowDefinitionId(), WorkflowMetrics.RetryTrigger.MANUAL);

        var what = new StringBuilder("ran the process again from step ").append(command.stepId());
        if (!movedBackTo.isEmpty()) {
            what.append(" (moved back to LOCK step(s) ").append(movedBackTo)
                    .append(": their lock is held by another process, so the section re-runs and queues for it)");
        }
        what.append("; reopened ").append(reopened.isEmpty() ? "nothing" : String.join(", ", reopened));
        if (!reacquired.isEmpty()) {
            what.append("; re-took lock(s) ").append(String.join(", ", reacquired));
        }
        if (reopenedProcess != process && ProcessStatus.ERROR.equals(process.getStatus())) {
            what.append("; the process is running again");
        }
        audit.performed(reopenedProcess, null, OperatorAudit.RETRY_FROM_STEP, command.requestedBy(), command.reason(),
                what.toString());

        if (!ProcessStatus.PAUSED.equals(reopenedProcess.getStatus())) {
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
        return children;
    }

    /**
     * Makes sure the re-run never works inside a step-level critical section without its lock. For each
     * section opened by a LOCK step that is not reopened, and not closed by an UNLOCK that is not
     * reopened either, whose steps include reopened ones: take the lock again (reentrant, never queued).
     * When it is busy, reopen the LOCK step too — the section re-runs from its LOCK, which queues.
     */
    private ProcessOperationRules.RetryPlan withCriticalSections(Process process, ProcessOperationRules.RetryPlan plan,
                                                                 List<StepExecution> steps,
                                                                 List<String> reacquired, List<String> movedBackTo) {
        var stepIds = new LinkedHashSet<>(plan.stepIds());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var lock : steps) {
                if (stepIds.contains(lock.getStepId()) || !StepExecutionStatus.COMPLETED.equals(lock.getStatus())) {
                    continue;
                }
                var step = ProcessOperationRules.stepOf(lock);
                if (step == null || !StepType.LOCK.equals(step.type())) {
                    continue;
                }
                var name = lockName(step, lock);
                var key = LockKeyResolver.resolve(step, process);
                if (key == null || closedOutside(name, key, process, steps, stepIds)) {
                    continue;
                }
                var section = ProcessOperationRules.downstreamOf(lock.getStepId(), steps);
                if (section.stream().noneMatch(stepIds::contains)) {
                    continue;
                }
                var label = name + "/" + key;
                if (lockService.tryAcquire(name, key, process.getId()) == LockService.Outcome.ACQUIRED) {
                    if (!reacquired.contains(label)) {
                        reacquired.add(label);
                    }
                } else {
                    stepIds.addAll(section);
                    movedBackTo.add(lock.getStepId());
                    changed = true;
                    break;
                }
            }
        }
        if (stepIds.equals(plan.stepIds())) {
            return plan;
        }
        return new ProcessOperationRules.RetryPlan(Set.copyOf(stepIds),
                steps.stream().filter(se -> stepIds.contains(se.getStepId())).toList());
    }

    /** Whether an UNLOCK of the same lock, not reopened, has already closed the section. */
    private static boolean closedOutside(String name, String key, Process process, List<StepExecution> steps,
                                         Set<String> reopened) {
        return steps.stream()
                .filter(se -> !reopened.contains(se.getStepId()))
                .filter(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus()))
                .anyMatch(se -> {
                    var step = ProcessOperationRules.stepOf(se);
                    return step != null && StepType.UNLOCK.equals(step.type())
                            && name.equals(lockName(step, se))
                            && Objects.equals(key, LockKeyResolver.resolve(step, process));
                });
    }

    /** As the step-over names it: a step-level lock's domain defaults to its definition. */
    private static String lockName(Step step, StepExecution stepExecution) {
        return step.lockName() != null && !step.lockName().isBlank()
                ? step.lockName() : stepExecution.getWorkflowDefinitionId();
    }

    /** A step that has never left the starting gate: nothing to reset. */
    private static boolean isFresh(StepExecution stepExecution) {
        return StepExecutionStatus.CREATED.equals(stepExecution.getStatus())
                && stepExecution.getStartedAt() == null
                && stepExecution.getAttemptCount() == 0;
    }

    /** Floored, as the status recompute floors it. */
    private static int completion(List<StepExecution> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        long completed = steps.stream().filter(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus())).count();
        return (int) ((100L * completed) / steps.size());
    }
}
