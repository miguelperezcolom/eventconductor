package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.JoinType;
import io.mateu.workflow.domain.aggregates.Precondition;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Cleans up after branches the flow abandoned, in a process that has <b>not</b> failed — the saga
 * rollback ({@link CompensationService}) covers a failed one. Two ways a branch is abandoned:
 *
 * <ul>
 *   <li><b>Another branch reached END.</b> The END cancels whatever was still waiting or running.
 *       A step it cancelled whose work completed anyway — the worker had already booked, the child
 *       process had already finished — reports COMPLETED after the process ended, and that work must
 *       be undone: it is compensated, always. With {@code compensateAbandoned: true} on the END, the
 *       compensable steps that completed <em>in time</em> on the branches it cut short are compensated
 *       too.</li>
 *   <li><b>An XOR join went on with another branch</b> — only with {@code compensateLosers: true} on
 *       the join (without it the other branches run on, as they always have). The losing branches are
 *       cancelled where they stand, and every compensable step of theirs that completed, in time or
 *       late, is compensated.</li>
 * </ul>
 *
 * <p>A cleanup runs the step's compensation over the units to undo, newest first, one compensation at
 * a time, marked as a cleanup ({@link StepExecution#markCleanup(String)}): it does not move the process
 * status, it does not trigger a rollback, and if it fails it is an incident in the Errors tab, not a
 * failure of the process.
 *
 * <p>Pure: everything is derived from the persisted executions, so the caller may ask on every event —
 * what is already undone (a completed compensation run that covers the unit) is never undone twice.
 */
@Service
public class AbandonedBranchService {

    /** What to do now: steps to cancel (losing branches of a cleaning-up XOR join), and at most one cleanup to start. */
    public record Plan(List<StepExecution> toCancel, Cleanup cleanup) {
        static final Plan NOTHING = new Plan(List.of(), null);

        public boolean isEmpty() {
            return toCancel.isEmpty() && cleanup == null;
        }
    }

    /**
     * Run {@code compensation} — a CREATED execution, or a finished one to run again as a new pass — once
     * per unit, newest first.
     */
    public record Cleanup(StepExecution compensation, List<StepExecution> units, String reason) {
        public boolean runsAgain() {
            return compensation.getStatus() != StepExecutionStatus.CREATED;
        }
    }

    private static final Set<StepExecutionStatus> LIVE = Set.of(StepExecutionStatus.CREATED, StepExecutionStatus.PENDING,
            StepExecutionStatus.RUNNING, StepExecutionStatus.AWAITING_RETRY, StepExecutionStatus.WAITING_ON_LOCK);

    public Plan decide(Process process, List<StepExecution> executions) {
        if (process.getStatus() != ProcessStatus.RUNNING && process.getStatus() != ProcessStatus.COMPLETED) {
            return Plan.NOTHING;
        }
        var current = StepExecutions.current(executions);
        Map<String, StepExecution> byStepId = new HashMap<>();
        Map<String, Step> steps = new HashMap<>();
        for (var execution : current) {
            byStepId.put(execution.getStepId(), execution);
            try {
                steps.put(execution.getStepId(), pojoFromJson(execution.getStepJson(), Step.class));
            } catch (RuntimeException e) {
                // A step whose JSON cannot be read takes no part in the graph.
            }
        }
        var graph = new Graph(steps);

        // Units to undo, by compensable step, with what abandoned them.
        Map<String, String> reasonByStep = new HashMap<>();
        Map<String, List<StepExecution>> unitsByStep = new HashMap<>();
        List<StepExecution> toCancel = new ArrayList<>();

        for (var execution : current) {
            var step = steps.get(execution.getStepId());
            if (step == null || execution.getStatus() != StepExecutionStatus.COMPLETED) {
                continue;
            }
            if (step.type() == StepType.JOIN && step.joinType() == JoinType.XOR && step.compensatesLosers()) {
                var losing = losingBranches(execution, step, byStepId, graph);
                var reason = "xor:" + step.id();
                for (var stepId : losing) {
                    var loser = byStepId.get(stepId);
                    if (loser != null && LIVE.contains(loser.getStatus())) {
                        toCancel.add(loser);
                    }
                    collect(stepId, steps, executions, reason, unitsByStep, reasonByStep, u -> true);
                }
            }
            if (step.type() == StepType.END) {
                var kept = graph.ancestorsInclusive(Set.of(step.id()));
                var endedAt = execution.getFinishedAt();
                var reason = "end:" + step.id();
                for (var stepId : steps.keySet()) {
                    if (step.compensatesAbandoned() && !kept.contains(stepId) && graph.descendants(stepId).stream()
                            .map(byStepId::get)
                            .anyMatch(d -> d != null && d.getStatus() == StepExecutionStatus.CANCELLED)) {
                        // Opted in: what a branch the END cut short completed in time.
                        collect(stepId, steps, executions, reason, unitsByStep, reasonByStep, u -> true);
                    } else {
                        // Always: work that completed only after the END had cancelled it — wherever it
                        // sits, even on a path into the END (the losing link of an XOR join on the way).
                        collect(stepId, steps, executions, reason, unitsByStep, reasonByStep,
                                u -> endedAt != null && u.getFinishedAt() != null && u.getFinishedAt().isAfter(endedAt));
                    }
                }
            }
        }
        if (!toCancel.isEmpty()) {
            // Cancel first; the cleanup is decided on the next event, once nothing of those branches runs.
            return new Plan(toCancel, null);
        }
        if (unitsByStep.isEmpty()) {
            return Plan.NOTHING;
        }
        // One compensation at a time, as in a rollback.
        boolean anyCompensationInFlight = steps.values().stream()
                .map(Step::compensationStepId)
                .filter(id -> id != null && !id.isBlank())
                .map(byStepId::get)
                .anyMatch(c -> c != null && c.getStatus().keepsTheProcessInFlight());
        if (anyCompensationInFlight) {
            return Plan.NOTHING;
        }
        // Newest unit first across steps, so the latest work is undone first.
        var ordered = unitsByStep.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<StepExecution>> e) -> e.getValue().get(0).getFinishedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
        for (var entry : ordered) {
            var step = steps.get(entry.getKey());
            var compensation = byStepId.get(step.compensationStepId());
            if (compensation == null) {
                continue;
            }
            if ((compensation.getStatus() == StepExecutionStatus.ERROR || compensation.getStatus() == StepExecutionStatus.TIMEOUT)) {
                // It already failed: an incident for an operator, not something to retry on every event.
                continue;
            }
            var notUndone = CompensationService.notYetUndone(entry.getValue(), step, executions);
            if (notUndone.isEmpty()) {
                continue;
            }
            if (compensation.getStatus() == StepExecutionStatus.CANCELLED && compensation.getStartedAt() != null) {
                continue;
            }
            return new Plan(List.of(), new Cleanup(compensation, notUndone, reasonByStep.get(entry.getKey())));
        }
        return Plan.NOTHING;
    }

    private static void collect(String stepId, Map<String, Step> steps, List<StepExecution> executions, String reason,
                                Map<String, List<StepExecution>> unitsByStep, Map<String, String> reasonByStep,
                                java.util.function.Predicate<StepExecution> which) {
        var step = steps.get(stepId);
        if (step == null || !step.compensable() || step.compensationStepId() == null || step.compensationStepId().isBlank()) {
            return;
        }
        var units = CompensationService.unitsOf(stepId, executions).stream().filter(which).toList();
        if (units.isEmpty()) {
            return;
        }
        var merged = new LinkedHashSet<>(unitsByStep.getOrDefault(stepId, List.of()));
        merged.addAll(units);
        unitsByStep.put(stepId, merged.stream()
                .sorted(Comparator.comparing(StepExecution::getFinishedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList());
        reasonByStep.putIfAbsent(stepId, reason);
    }

    /**
     * The steps of the branches an XOR join did not go on with: those that lead into it only through an
     * incoming link that was not satisfied when it fired. A branch's steps are the ancestors of that
     * link's step, minus everything that also leads to a winning link (the shared part upstream of the
     * split), and minus any step that also leads somewhere other than this join — that one is still
     * needed by the rest of the flow.
     */
    static Set<String> losingBranches(StepExecution join, Step step, Map<String, StepExecution> byStepId, Graph graph) {
        var firedAt = join.getFinishedAt() != null ? join.getFinishedAt() : join.getStartedAt();
        Set<String> winners = new HashSet<>();
        Set<String> losers = new HashSet<>();
        for (Precondition link : step.resolvedPreconditions()) {
            var source = byStepId.get(link.stepId());
            boolean won = source != null && source.getStatus() == StepExecutionStatus.COMPLETED
                    && (firedAt == null || source.getFinishedAt() == null || !source.getFinishedAt().isAfter(firedAt));
            (won ? winners : losers).add(link.stepId());
        }
        losers.removeAll(winners);
        if (winners.isEmpty() || losers.isEmpty()) {
            return Set.of();
        }
        var shared = graph.ancestorsInclusive(winners);
        Set<String> branch = new HashSet<>(graph.ancestorsInclusive(losers));
        branch.removeAll(shared);
        // Prune what also feeds something outside the branch and this join.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var stepId : List.copyOf(branch)) {
                for (var successor : graph.successors(stepId)) {
                    if (!branch.contains(successor) && !successor.equals(step.id())) {
                        branch.remove(stepId);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return branch;
    }

    /** The definition's links, read off the steps' preconditions. */
    static final class Graph {
        private final Map<String, Set<String>> predecessors = new HashMap<>();
        private final Map<String, Set<String>> successors = new HashMap<>();

        Graph(Map<String, Step> steps) {
            steps.forEach((id, step) -> {
                for (var link : step.resolvedPreconditions()) {
                    predecessors.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(link.stepId());
                    successors.computeIfAbsent(link.stepId(), k -> new LinkedHashSet<>()).add(id);
                }
            });
        }

        Set<String> successors(String stepId) {
            return successors.getOrDefault(stepId, Set.of());
        }

        Set<String> ancestorsInclusive(Set<String> from) {
            return walk(from, predecessors, true);
        }

        Set<String> descendants(String from) {
            return walk(Set.of(from), successors, false);
        }

        private static Set<String> walk(Set<String> from, Map<String, Set<String>> edges, boolean inclusive) {
            Set<String> seen = new HashSet<>();
            var queue = new ArrayDeque<String>(from);
            if (inclusive) {
                seen.addAll(from);
            }
            while (!queue.isEmpty()) {
                for (var next : edges.getOrDefault(queue.poll(), Set.of())) {
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return seen;
        }
    }
}
