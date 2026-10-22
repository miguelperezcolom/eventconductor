package io.mateu.workflow.infra.in.monitoring;

import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.dtos.MessageType;
import java.time.LocalDateTime;
import java.util.*;
import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/** Frozen topology and masked execution overlays shared by the REST API and the console. */
public final class ProcessGraphProjection {
    private ProcessGraphProjection() {}
    public record Graph(WorkflowDefinition definition, Map<String, Object> overlay) {}

    public static Graph project(WorkflowDefinition definition, Process process,
                                List<StepExecution> steps, List<LogMessage> logs) {
        var byStep = new HashMap<String, StepExecution>();
        for (var se : io.mateu.workflow.domain.services.StepExecutions.current(steps)) {
            byStep.merge(se.getStepId(), se, (a, b) -> {
                int ra = statusRank(a.getStatus()), rb = statusRank(b.getStatus());
                return rb != ra ? (rb > ra ? b : a) : (b.getAttemptCount() >= a.getAttemptCount() ? b : a);
            });
        }
        var errors = latestErrorByStepExecution(logs);
        var order = executionOrder(byStep.values());
        var overlay = new HashMap<String, Object>();
        var sensitive = io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process);
        byStep.forEach((id, execution) -> {
            var entry = overlayEntry(execution, errors.get(execution.id()), sensitive);
            if (order.containsKey(id)) entry.put("order", order.get(id));
            putRunsAndIterations(entry, execution, steps);
            overlay.put(id, entry);
        });
        return new Graph(withInjectedSteps(definition, steps), overlay);
    }

    public static io.mateu.workflow.domain.aggregates.WorkflowDefinition safeDefinition(String definitionJson) {
        try {
            return definitionJson == null || definitionJson.isBlank() ? null
                    : pojoFromJson(definitionJson, io.mateu.workflow.domain.aggregates.WorkflowDefinition.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The definition augmented with the steps a DYNAMIC step injected into this process at runtime.
     *
     * <p>Injected steps are not in the definition — each lives only as a step execution carrying its
     * own frozen {@code stepJson} and marked with {@code injectedByStepExecutionId}. This appends
     * those (deserialised back to {@link io.mateu.workflow.domain.aggregates.Step}s) after the
     * declared steps, so the graph value is the process's real topology. When nothing was injected
     * the definition is returned untouched, so the common case pays nothing and behaves exactly as
     * before.
     */
    public static io.mateu.workflow.domain.aggregates.WorkflowDefinition withInjectedSteps(
            io.mateu.workflow.domain.aggregates.WorkflowDefinition def, List<StepExecution> stepExecutions) {
        var declaredIds = def.steps().stream()
                .map(io.mateu.workflow.domain.aggregates.Step::id)
                .collect(java.util.stream.Collectors.toSet());
        // One entry per injected step id (retries share it), in execution order, skipping any id the
        // definition already carries so an injected step never doubles a declared one.
        var injectedById = new java.util.LinkedHashMap<String, io.mateu.workflow.domain.aggregates.Step>();
        for (var se : stepExecutions) {
            if (se.getInjectedByStepExecutionId() == null) continue;
            var step = safeStep(se.getStepJson());
            if (step == null || step.id() == null) continue;
            if (declaredIds.contains(step.id()) || injectedById.containsKey(step.id())) continue;
            injectedById.put(step.id(), step);
        }
        if (injectedById.isEmpty()) return def;
        var allSteps = new ArrayList<>(def.steps());
        allSteps.addAll(injectedById.values());
        return def.withSteps(allSteps);
    }

    /**
     * How a step's row reads in the Steps tab: its id, and for a loop's later pass or a forEach
     * iteration which one it is — "reserve · item 2", "poll · run 3".
     */
    public static String rowName(StepExecution se) {
        if (se.isIteration()) {
            return se.getStepId() + " · item " + (se.getItemIndex() == null ? "?" : se.getItemIndex())
                    + (se.getIteration() > 0 ? " (run " + (se.getIteration() + 1) + ")" : "");
        }
        if (se.getIteration() > 0) {
            return se.getStepId() + " · run " + (se.getIteration() + 1);
        }
        return se.getStepId();
    }

    /**
     * Adds to a node's overlay how many times its step ran ({@code runs}, for a loop) and how its
     * forEach's iterations stand ({@code iterations}: total, completed, failed, active).
     */
    public static void putRunsAndIterations(Map<String, Object> entry, StepExecution current, List<StepExecution> all) {
        long runs = all.stream()
                .filter(e -> e.getParentExecutionId() == null && current.getStepId().equals(e.getStepId())
                        && !io.mateu.workflow.domain.aggregates.StepExecution.LOOP_DISCARDED.equals(e.getLoopDecision())
                        && (e.getStartedAt() != null || e.getStatus() == StepExecutionStatus.COMPLETED))
                .count();
        if (runs > 1) {
            entry.put("runs", runs);
        }
        if (current.isContainer() && current.totalIterations() >= 0) {
            var iterations = io.mateu.workflow.domain.services.StepExecutions.iterationsOf(current, all);
            var summary = new HashMap<String, Object>();
            summary.put("total", current.totalIterations());
            summary.put("completed", iterations.stream().filter(i -> i.getStatus() == StepExecutionStatus.COMPLETED).count());
            summary.put("failed", iterations.stream().filter(i -> i.getStatus() == StepExecutionStatus.ERROR
                    || i.getStatus() == StepExecutionStatus.TIMEOUT).count());
            summary.put("active", iterations.stream().filter(i -> i.getStatus().keepsTheProcessInFlight()).count());
            entry.put("iterations", summary);
        }
    }

    /** Overlay state token understood by the graph component. */
    public static String overlayState(StepExecutionStatus status) {
        return switch (status) {
            case REVIEW_REQUIRED -> "REVIEW_REQUIRED";
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case ERROR -> "ERROR";
            // TIMEOUT gets its own token (not folded into ERROR): the graph draws it with its own
            // amber clock badge, since a step that ran out of time is not the same as one that failed.
            case TIMEOUT -> "TIMEOUT";
            case CANCELLED -> "CANCELLED";
            // A step parked in a lock queue gets its own token so the graph can badge it distinctly
            // (a padlock, not a plain spinner) — it is waiting for a lock, not for a worker.
            case WAITING_ON_LOCK -> "WAITING_ON_LOCK";
            // A step waiting out its retry backoff reads as pending work on the graph, not as an
            // error — it failed but is going to run again, and the overlay reason spells that out.
            case CREATED, PENDING, AWAITING_RETRY -> "PENDING";
        };
    }

    /** How "telling" a status is when several executions exist for one step (retries). */
    public static int statusRank(StepExecutionStatus status) {
        return switch (status) {
            case REVIEW_REQUIRED -> 6;
            case ERROR, TIMEOUT -> 5;
            case RUNNING -> 4;
            // A pending retry outranks a plain pending: it is the more telling of the two when a
            // step has several executions, because it says the last attempt failed.
            case AWAITING_RETRY -> 4;
            case PENDING -> 3;
            // Parked on a lock: waiting work, as telling as a plain pending.
            case WAITING_ON_LOCK -> 3;
            case CREATED -> 2;
            case COMPLETED -> 1;
            case CANCELLED -> 0;
        };
    }

    /**
     * The overlay entry the graph hover reads: the step's state plus the consolidated "why it is
     * here" and the detail an operator needs to answer it without opening the code — the parked
     * reason, the last error, retry count, what it awaits, deadlines and its variable snapshot.
     */
    public static Map<String, Object> overlayEntry(StepExecution se, String error) {
        return overlayEntry(se, error, io.mateu.workflow.crypto.SensitiveNames.NONE);
    }

    /** {@link #overlayEntry(StepExecution, String)}, the step's sensitive variables masked. */
    public static Map<String, Object> overlayEntry(StepExecution se, String error, io.mateu.workflow.crypto.SensitiveNames sensitive) {
        var step = safeStep(se.getStepJson());
        var status = se.getStatus();
        var entry = new HashMap<String, Object>();
        // A step the run has not reached — CREATED, never attempted — has no state on the graph:
        // drawn as pending it took the marching "waiting" border, and every step downstream of the
        // current one looked as if the process were waiting on it. Only work that is really waiting
        // (started, or queued for a retry) marches; the rest is dimmed as not visited yet.
        if (status != StepExecutionStatus.CREATED || se.getAttemptCount() > 0) {
            entry.put("state", overlayState(status));
        }
        entry.put("reason", reasonFor(se, step, error));
        // A step a DYNAMIC step added at runtime, not one the definition declared. The graph badges
        // these so an operator can tell what the running process grew from what its author wrote;
        // the injector id is carried too, for tooling that wants to trace it back.
        if (se.getInjectedByStepExecutionId() != null) {
            entry.put("injected", true);
            entry.put("injectedBy", se.getInjectedByStepExecutionId());
        }
        if (status == StepExecutionStatus.RUNNING) entry.put("active", true);
        if (error != null) entry.put("error", error);
        if (se.getAttemptCount() > 0) entry.put("attempt", se.getAttemptCount());
        if (step != null && step.retries() > 0) entry.put("maxRetries", step.retries());
        if (se.getAwaitingMessageName() != null) entry.put("awaitingMessage", se.getAwaitingMessageName());
        if (se.getAwaitingCorrelationKey() != null) entry.put("correlationKey", se.getAwaitingCorrelationKey());
        if (se.getDeadlineAt() != null) entry.put("deadlineAt", se.getDeadlineAt().toString());
        if (se.getStartedAt() != null) entry.put("startedAt", se.getStartedAt().toString());
        if (se.getWorkerId() != null) entry.put("worker", se.getWorkerId());
        var vars = se.getVariables();
        if (vars != null && !vars.isEmpty()) {
            entry.put("variables", vars.stream()
                    .limit(12)
                    .map(v -> Map.of("name", v.name() == null ? "" : v.name(),
                            "value", v.value() == null ? "" : io.mateu.workflow.application.services.SensitiveVariableMasking.shown(v.name(), v.value(), sensitive)))
                    .toList());
        }
        return entry;
    }

    /**
     * What ran first, second, third — by step id, for the numbers the diagram puts on its nodes.
     *
     * <p>The graph draws the shape of a workflow, and the shape does not say what order a
     * particular process actually took: two branches drawn side by side ran in some order, a loop
     * drawn as one node ran several times, and a step that was skipped is drawn exactly where it
     * would have been. A tick answers "did this run"; the number answers "when", which is the
     * question an operator reading a finished process is usually asking.
     *
     * <p>Ordered by {@code startedAt}, since that is when a step took its turn — not by when it
     * finished, which would number a slow first step after the quick one that followed it. A step
     * that has no {@code startedAt} is ordered by {@code finishedAt} instead: not every step gets
     * one, since it is stamped where a task is dispatched to a worker and an END step is never
     * dispatched anywhere. Leaving those unnumbered was the first attempt and it was worse than no
     * numbers at all — the browser test caught an END wearing a tick and no number, which reads as
     * a step that never ran.
     *
     * <p>Steps with neither timestamp are left out and get no number, which is the honest reading:
     * an unnumbered node is one that has not had its turn.
     *
     * <p>Ties are broken by step id so that two steps starting in the same instant — which
     * parallel branches routinely do — are numbered the same way on every poll. An arbitrary but
     * stable order beats a number that changes under the reader every two seconds.
     */
    public static Map<String, Integer> executionOrder(Collection<StepExecution> executions) {
        var started = executions.stream()
                .filter(se -> whenItRan(se) != null)
                .sorted(Comparator.comparing(ProcessGraphProjection::whenItRan)
                        .thenComparing(StepExecution::getStepId))
                .toList();
        var order = new HashMap<String, Integer>();
        for (var i = 0; i < started.size(); i++) {
            order.put(started.get(i).getStepId(), i + 1);
        }
        return order;
    }

    /** When a step took its turn: when it started, or — for the steps nothing dispatches — when it
     * finished. Null for a step that has done neither. */
    public static LocalDateTime whenItRan(StepExecution se) {
        return se.getStartedAt() != null ? se.getStartedAt() : se.getFinishedAt();
    }

    /** Latest error message per step execution id, so a failed step can show its own cause. */
    public static Map<String, String> latestErrorByStepExecution(List<LogMessage> logs) {
        var latest = new HashMap<String, String>();
        var when = new HashMap<String, LocalDateTime>();
        for (var lm : logs) {
            if (!MessageType.isError(lm.getMessageType())) continue;
            var eid = lm.getStepExecutionId();
            if (eid == null) continue;
            var ts = lm.getTimestamp();
            if (!when.containsKey(eid) || (ts != null && ts.isAfter(when.get(eid)))) {
                when.put(eid, ts);
                latest.put(eid, lm.getMessage());
            }
        }
        return latest;
    }

    /** Human-legible answer to "why is this step here?", read straight from the DSL vocabulary. */
    public static String reasonFor(StepExecution se, io.mateu.workflow.domain.aggregates.Step step, String error) {
        var type = step != null ? step.type() : null;
        return switch (se.getStatus()) {
            case REVIEW_REQUIRED -> "Agent invocation requires reconciliation before the process can resume";
            case RUNNING -> "Running" + (se.getWorkerId() != null ? " on worker " + se.getWorkerId() : "");
            case CREATED -> se.getAttemptCount() > 0 ? "Queued for retry" : "Queued, not started yet";
            case PENDING -> pendingReason(se, type);
            case AWAITING_RETRY -> "Waiting to retry (attempt " + (se.getAttemptCount() + 1) + ")"
                    + (se.getDeadlineAt() != null ? " at " + se.getDeadlineAt() : "");
            case ERROR -> (se.getAttemptCount() > 0 ? "Failed on attempt " + (se.getAttemptCount() + 1) : "Failed")
                    + (error != null ? ": " + error : "");
            case TIMEOUT -> "Timed out" + (se.getDeadlineAt() != null ? " (deadline " + se.getDeadlineAt() + ")" : "");
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            case WAITING_ON_LOCK -> "Waiting for a lock";
        };
    }

    public static String pendingReason(StepExecution se, StepType type) {
        if (se.getAwaitingMessageName() != null) {
            return "Waiting for message '" + se.getAwaitingMessageName() + "'"
                    + (se.getAwaitingCorrelationKey() != null ? " with key '" + se.getAwaitingCorrelationKey() + "'" : "");
        }
        if (type == StepType.TIMER && se.getDeadlineAt() != null) return "Waiting for timer until " + se.getDeadlineAt();
        if (type == StepType.USER_TASK) {
            var assignment = se.assignment();
            var next = se.nextEscalationAt();
            return "Waiting for a human task"
                    + (assignment != null && assignment.assignee() != null ? " (assigned to " + assignment.assignee() + ")" : "")
                    + (se.escalationsFired() > 0 ? ", escalated " + se.escalationsFired() + "x" : "")
                    + (next != null ? ", next escalation at " + next : "");
        }
        if (type == StepType.PROCESS) return "Waiting for a child process to finish";
        if (se.getDeadlineAt() != null) return "Waiting (times out at " + se.getDeadlineAt() + ")";
        return "Waiting for its preconditions";
    }

    /** Deserialize the frozen step definition an execution ran with; null if it cannot be read. */
    public static io.mateu.workflow.domain.aggregates.Step safeStep(String stepJson) {
        try {
            return stepJson == null ? null : pojoFromJson(stepJson, io.mateu.workflow.domain.aggregates.Step.class);
        } catch (Exception e) {
            return null;
        }
    }

}
