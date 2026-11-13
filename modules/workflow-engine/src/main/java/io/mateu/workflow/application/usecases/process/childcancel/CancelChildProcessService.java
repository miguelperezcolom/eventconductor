package io.mateu.workflow.application.usecases.process.childcancel;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.rollback.RollBackProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.domain.services.StepExecutions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Propagates cancellation parent→child: when a PROCESS step execution reaches a terminal
 * status other than COMPLETED (CANCELLED, ERROR or TIMEOUT), the child process it spawned
 * must not keep running for a parent that will never consume its result. Must be called from
 * every seam where a step execution is saved with one of those statuses.
 *
 * <p>How the child stops depends on why the parent cut it short:
 *
 * <ul>
 *   <li><b>The engine cut it</b> while undoing work — the parent is rolling back, the child's
 *       iteration was cancelled by a forEach failing fast, or the parent's flow abandoned the branch
 *       with {@code compensateAbandoned} on an END (an XOR join's {@code compensateLosers} calls
 *       {@link #rollBackChildrenOf} directly). Then the child is <b>rolled back</b>
 *       ({@link RollBackProcessUseCase}): its in-flight work is cancelled and its completed compensable
 *       steps are compensated, recursively.</li>
 *   <li><b>Anything else</b> — an operator cancelling the parent ({@link #stepCancelledWithItsProcess}),
 *       an END without {@code compensateAbandoned}, a forEach completing early, a plain failure with
 *       nothing to roll back: the child is <b>cancelled</b>, as always ({@link CancelProcessUseCase},
 *       no rollback).</li>
 * </ul>
 *
 * <p>The child is found through its deterministic businessKey
 * {@code "parent:" + stepExecutionId} (or {@code #2}, {@code #3}, … for a step that ran its child
 * again — see {@link ChildProcesses}). Either way the cancelled child's own PROCESS steps re-enter
 * this service, which is exactly how the cascade reaches grandchildren.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancelChildProcessService {

    private static final Set<StepExecutionStatus> CHILD_CANCELLING_STATUSES = Set.of(
            StepExecutionStatus.CANCELLED,
            StepExecutionStatus.ERROR,
            StepExecutionStatus.TIMEOUT);

    final ProcessRepository processRepository;
    // ObjectProvider (not direct injection): CancelProcessUseCase depends on this service to
    // cascade into grandchildren, so a direct constructor dependency would close a cycle.
    final ObjectProvider<CancelProcessUseCase> cancelProcessUseCase;
    final ObjectProvider<RollBackProcessUseCase> rollBackProcessUseCase;
    final ObjectProvider<StepExecutionRepository> stepExecutionRepository;
    final CompensationService compensationService;

    /** A PROCESS step the engine ended other than COMPLETED: its live child is rolled back or cancelled. */
    public void stepReachedTerminalStatus(StepExecution stepExecution) {
        var children = liveChildrenOf(stepExecution);
        if (children.isEmpty()) {
            return;
        }
        var reason = rollbackReason(stepExecution);
        children.forEach(child -> stop(child, stepExecution, reason));
    }

    /**
     * A PROCESS step cancelled because an operator cancelled its process: the child is cancelled, never
     * rolled back — cancelling a process does not undo what it did, and that holds down the tree.
     */
    public void stepCancelledWithItsProcess(StepExecution stepExecution) {
        liveChildrenOf(stepExecution).forEach(child -> stop(child, stepExecution, null));
    }

    /** A PROCESS step the engine cancelled to undo its branch: its live child is rolled back. */
    public void rollBackChildrenOf(StepExecution stepExecution, String reason) {
        liveChildrenOf(stepExecution).forEach(child -> stop(child, stepExecution, reason));
    }

    private void stop(Process child, StepExecution stepExecution, String rollbackReason) {
        if (rollbackReason != null) {
            log.info("Parent PROCESS step {} ended {} — rolling back child process {} ({})",
                    stepExecution.id(), stepExecution.getStatus(), child.getId(), rollbackReason);
            rollBackProcessUseCase.getObject().handle(child.getId(), rollbackReason);
        } else {
            log.info("Parent PROCESS step {} ended {} — cancelling child process {}",
                    stepExecution.id(), stepExecution.getStatus(), child.getId());
            cancelProcessUseCase.getObject().handle(new CancelProcessCommand(child.getId()));
        }
    }

    private List<Process> liveChildrenOf(StepExecution stepExecution) {
        if (stepExecution == null
                || !CHILD_CANCELLING_STATUSES.contains(stepExecution.getStatus())
                || stepExecution.getStepJson() == null) {
            // Only parse stepJson when the saved status is one of the three — this method
            // sits on hot save paths.
            return List.of();
        }
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (!StepType.PROCESS.equals(step.type())) {
            return List.of();
        }
        // Every child the step has run — a retried step's earlier children are finished, but the
        // one in flight may be any of them (a retry after a timeout re-attaches to its child).
        return ChildProcesses.of(processRepository, stepExecution.id()).stream()
                .filter(child -> ProcessStatus.PENDING.equals(child.getStatus())
                        || ProcessStatus.RUNNING.equals(child.getStatus())
                        || ProcessStatus.PAUSED.equals(child.getStatus()))
                .toList();
    }

    /**
     * Why the engine cut this PROCESS step short in a way that undoes the work under it, or null when
     * it did not (the child is then only cancelled). Read off the parent's persisted state, and only
     * when there is a live child to stop.
     */
    private String rollbackReason(StepExecution stepExecution) {
        var parent = processRepository.findById(stepExecution.getProcessId()).orElse(null);
        if (parent == null || ProcessStatus.CANCELLED.equals(parent.getStatus())) {
            return null;
        }
        var executions = stepExecutionRepository.getObject().findByProcessId(parent.getId());
        if (compensationService.rollsBack(parent, executions)) {
            return "its parent " + parent.getId() + " is rolling back";
        }
        if (stepExecution.isIteration() && StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus())
                && failedFast(stepExecution, executions)) {
            return "its forEach in " + parent.getId() + " failed fast";
        }
        if (StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus()) && endedCompensatingAbandoned(executions)) {
            return "its branch in " + parent.getId() + " was abandoned by an END with compensateAbandoned";
        }
        return null;
    }

    /** A cancelled iteration of a forEach that fails fast, one of whose iterations failed for good. */
    private static boolean failedFast(StepExecution iteration, List<StepExecution> executions) {
        var container = executions.stream()
                .filter(e -> e.getId().equals(iteration.getParentExecutionId())).findFirst().orElse(null);
        if (container == null) {
            return false;
        }
        var forEach = pojoFromJson(container.getStepJson(), Step.class).forEach();
        if (forEach == null || forEach.waitsForEveryIteration()) {
            return false;
        }
        return StepExecutions.iterationsOf(container, executions).stream()
                .anyMatch(i -> i.getStatus() == StepExecutionStatus.ERROR || i.getStatus() == StepExecutionStatus.TIMEOUT);
    }

    /** The parent's flow ended at an END that compensates the branches it abandons. */
    private static boolean endedCompensatingAbandoned(List<StepExecution> executions) {
        return StepExecutions.current(executions).stream()
                .filter(e -> e.getStatus() == StepExecutionStatus.COMPLETED)
                .map(e -> pojoFromJson(e.getStepJson(), Step.class))
                .anyMatch(step -> StepType.END.equals(step.type()) && step.compensatesAbandoned());
    }
}
