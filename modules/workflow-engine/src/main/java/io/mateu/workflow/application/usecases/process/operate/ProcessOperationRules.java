package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.domain.services.StepExecutions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * When an operator may change a live process by hand, decided once and asked twice: by the door that
 * takes the request (the REST API answers {@code 409} with the reason, the UI and MCP say it) and by
 * the use case that carries it out on the node that owns the process, under its lock — where the
 * process may have moved on since the door looked. Each {@code whyNot…} answers null when the
 * operation is allowed, or the reason it is not.
 *
 * <h2>The common rules</h2>
 *
 * <ul>
 *   <li>Only a <b>live</b> process is operated on: RUNNING, PAUSED or ERROR. PENDING has not started;
 *       COMPLETED, CANCELLED, COMPENSATED and COMPENSATION_FAILED are finished (a cancelled process is
 *       picked up again with retry or restart, which revive what the cancel stopped).</li>
 *   <li>A failed process whose <b>saga rollback is under way</b> — a compensation is about to run or is
 *       running — is not touched: the rollback is undoing what a manual completion or a re-run would
 *       build on. Once it ends the process is COMPENSATED or COMPENSATION_FAILED, finished either way.
 *       Setting variables is the exception, and is allowed: it moves no step.</li>
 * </ul>
 *
 * <h2>Loops and forEach</h2>
 *
 * <p>The operations reason over the process's <b>current</b> executions, as the orchestrator does
 * ({@link StepExecutions#current}): a loop's earlier passes are history (superseded) and are never
 * operated on; a forEach is one step, its container, whose iterations it drives. An iteration can be
 * completed by hand while its container is still running it; a whole forEach is completed, or run
 * again from, through its container.
 */
@Component
@RequiredArgsConstructor
public class ProcessOperationRules {

    /** The statuses a process is operated on in. */
    public static final Set<ProcessStatus> LIVE = EnumSet.of(
            ProcessStatus.RUNNING, ProcessStatus.PAUSED, ProcessStatus.ERROR);

    /** The statuses of a step that can be completed by hand: started and not finished, or failed. */
    public static final Set<StepExecutionStatus> COMPLETABLE = EnumSet.of(
            StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING, StepExecutionStatus.AWAITING_RETRY,
            StepExecutionStatus.ERROR, StepExecutionStatus.TIMEOUT);

    final CompensationService compensationService;

    // ── Set variables ────────────────────────────────────────────────────────────────────────────

    public String whyNotSetVariables(Process process) {
        return live(process);
    }

    // ── Complete a step ──────────────────────────────────────────────────────────────────────────

    /**
     * @param target the step execution to complete — already known to belong to the process
     */
    public String whyNotCompleteStep(Process process, StepExecution target, List<StepExecution> steps) {
        var notLive = live(process);
        if (notLive != null) {
            return notLive;
        }
        if (rollbackUnderway(process, steps)) {
            return "its saga rollback is under way";
        }
        if (target.isSuperseded()) {
            return "this execution is history: a later pass of the loop replaced it";
        }
        if (StepExecutionStatus.COMPLETED.equals(target.getStatus())) {
            return "the step is already COMPLETED";
        }
        if (target.isIteration()) {
            var container = steps.stream().filter(se -> se.getId().equals(target.getParentExecutionId()))
                    .findFirst().orElse(null);
            if (container == null || !StepExecutionStatus.PENDING.equals(container.getStatus())) {
                return "its forEach step is " + (container == null ? "gone" : container.getStatus())
                        + "; complete or retry the forEach step itself";
            }
        }
        if (!COMPLETABLE.contains(target.getStatus())) {
            return "the step is " + target.getStatus() + "; only a PENDING, RUNNING, AWAITING_RETRY, ERROR or"
                    + " TIMEOUT step is completed by hand";
        }
        var step = stepOf(target);
        if (step != null && (StepType.LOCK.equals(step.type()) || StepType.UNLOCK.equals(step.type()))) {
            // Completing a LOCK without taking the lock would let the process into a critical section
            // it does not own; an UNLOCK without releasing would keep the lock. The engine resolves
            // both on its own — there is nothing for a hand to do.
            return "a " + step.type() + " step is resolved by the engine, not by hand";
        }
        if (isCompensation(target, steps)) {
            return "the step is a compensation, which only the saga rollback runs";
        }
        return null;
    }

    // ── Retry from a step ────────────────────────────────────────────────────────────────────────

    /** What a retry from a step resets: the step ids, and their executions. */
    public record RetryPlan(Set<String> stepIds, List<StepExecution> executions) {
    }

    /**
     * The steps a retry from {@code stepId} reopens — that step and everything downstream of it — or
     * the reason it cannot be done (thrown as {@link Refused}).
     */
    public RetryPlan planRetryFrom(Process process, String stepId, List<StepExecution> allSteps) {
        var notLive = live(process);
        if (notLive != null) {
            throw new Refused(notLive);
        }
        // The current view: a loop's earlier passes are history, and a forEach is its container.
        var steps = StepExecutions.current(allSteps);
        var targets = steps.stream().filter(se -> stepId.equals(se.getStepId())).toList();
        if (targets.isEmpty()) {
            throw new Refused("the process has no step '" + stepId + "'");
        }
        if (rollbackUnderway(process, allSteps)) {
            throw new Refused("its saga rollback is under way");
        }
        if (targets.stream().anyMatch(target -> isCompensation(target, steps))) {
            throw new Refused("step '" + stepId + "' is a compensation, which only the saga rollback runs");
        }
        if (targets.stream().noneMatch(ProcessOperationRules::reached)) {
            throw new Refused("step '" + stepId + "' has not run yet; there is nothing to run again from it");
        }
        var stepIds = downstreamOf(stepId, steps);
        var executions = steps.stream().filter(se -> stepIds.contains(se.getStepId())).toList();
        // A failure the retry does not reopen still fails the process the moment it runs again: the
        // flow would be ERROR again at once, having re-run work for nothing.
        var failedElsewhere = steps.stream()
                .filter(se -> !stepIds.contains(se.getStepId()))
                .filter(ProcessOperationRules::blocksTheFlow)
                .map(StepExecution::getStepId)
                .distinct()
                .toList();
        if (!failedElsewhere.isEmpty()) {
            throw new Refused("step(s) " + failedElsewhere + " also failed and are not downstream of '" + stepId
                    + "'; retry from a step both depend on, or retry or complete those first");
        }
        if (!ProcessStatus.ERROR.equals(process.getStatus())
                && executions.stream().anyMatch(se -> StepExecutionStatus.WAITING_ON_LOCK.equals(se.getStatus()))) {
            // Queued in a lock's wait queue: the grant would complete a step that has been reset. A
            // failed process left every queue when it failed, so this only concerns a live one.
            throw new Refused("a step downstream of '" + stepId + "' is waiting in a lock queue; wait for it or retry"
                    + " from a step after it");
        }
        return new RetryPlan(stepIds, executions);
    }

    /** A retry that cannot be carried out, and why. */
    public static class Refused extends RuntimeException {
        public Refused(String why) {
            super(why);
        }
    }

    /**
     * {@code stepId} and every step that depends on it, transitively: a step depends on another when
     * one of its preconditions (links, a JOIN's branches, a CHOICE's outgoing edge) names it, or when
     * it is that step's on-timeout route. Read from the steps frozen on the executions — the process's
     * real topology, runtime-injected steps included — not from today's definition.
     */
    public static Set<String> downstreamOf(String stepId, List<StepExecution> steps) {
        Map<String, Set<String>> successors = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (var se : steps) {
            if (!seen.add(se.getStepId())) {
                continue;
            }
            var step = stepOf(se);
            if (step == null) {
                continue;
            }
            for (var precondition : step.resolvedPreconditions()) {
                successors.computeIfAbsent(precondition.stepId(), k -> new LinkedHashSet<>()).add(step.id());
            }
            if (step.onTimeoutStepId() != null && !step.onTimeoutStepId().isBlank()) {
                successors.computeIfAbsent(step.id(), k -> new LinkedHashSet<>()).add(step.onTimeoutStepId());
            }
        }
        var result = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(stepId);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (result.add(current)) {
                queue.addAll(successors.getOrDefault(current, Set.of()));
            }
        }
        return result;
    }

    // ── Shared ───────────────────────────────────────────────────────────────────────────────────

    private static String live(Process process) {
        if (process.getStatus() == null || !LIVE.contains(process.getStatus())) {
            return "only a RUNNING, PAUSED or ERROR process is operated on by hand";
        }
        return null;
    }

    /** A failed process whose saga rollback has a compensation still to run or in flight. */
    public boolean rollbackUnderway(Process process, List<StepExecution> steps) {
        if (!ProcessStatus.ERROR.equals(process.getStatus())) {
            return false;
        }
        var outcome = compensationService.decide(steps).outcome();
        return outcome == CompensationService.Outcome.RUN || outcome == CompensationService.Outcome.WAITING;
    }

    /** A final failure that holds the flow: ERROR, or a TIMEOUT that does not route anywhere. */
    public static boolean blocksTheFlow(StepExecution se) {
        if (StepExecutionStatus.ERROR.equals(se.getStatus())) {
            return true;
        }
        if (StepExecutionStatus.TIMEOUT.equals(se.getStatus())) {
            var step = stepOf(se);
            return step == null || step.onTimeoutStepId() == null || step.onTimeoutStepId().isBlank();
        }
        return false;
    }

    /** Whether the step has ever left the starting gate — run, or been sent to run. */
    static boolean reached(StepExecution se) {
        return switch (se.getStatus()) {
            case CREATED -> se.getStartedAt() != null;
            // A branch not taken is CANCELLED without ever having started.
            case CANCELLED -> se.getStartedAt() != null;
            default -> true;
        };
    }

    /** Whether the step is some other step's compensation. */
    static boolean isCompensation(StepExecution target, List<StepExecution> steps) {
        var seen = new HashSet<String>();
        for (var se : steps) {
            if (!seen.add(se.getStepId())) {
                continue;
            }
            var step = stepOf(se);
            if (step != null && target.getStepId().equals(step.compensationStepId())) {
                return true;
            }
        }
        return false;
    }

    static Step stepOf(StepExecution se) {
        try {
            return se.getStepJson() == null ? null : pojoFromJson(se.getStepJson(), Step.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
