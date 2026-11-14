package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AssertJ assertions on a {@link TestProcess}. Each assertion reads the process from the engine at
 * the moment it runs, and its failure message shows what the process looked like.
 */
public class ProcessAssert extends AbstractAssert<ProcessAssert, TestProcess> {

    ProcessAssert(TestProcess actual) {
        super(actual, ProcessAssert.class);
    }

    // ---------------------------------------------------------------- status

    public ProcessAssert hasStatus(ProcessStatus expected) {
        isNotNull();
        var status = actual.status();
        if (status != expected) {
            failWithMessage("Expected %s to be %s but it is %s.%n%s", actual, expected, status, describe());
        }
        return this;
    }

    /** Ran to its END. */
    public ProcessAssert isCompleted() {
        return hasStatus(ProcessStatus.COMPLETED);
    }

    /** Failed and rolled back: every compensation ran. */
    public ProcessAssert isCompensated() {
        return hasStatus(ProcessStatus.COMPENSATED);
    }

    /** Failed, and a compensation failed too. */
    public ProcessAssert isCompensationFailed() {
        return hasStatus(ProcessStatus.COMPENSATION_FAILED);
    }

    /** Ended in ERROR (nothing to compensate, or no compensation declared). */
    public ProcessAssert isFailed() {
        return hasStatus(ProcessStatus.ERROR);
    }

    public ProcessAssert isCancelled() {
        return hasStatus(ProcessStatus.CANCELLED);
    }

    /** Still going: RUNNING (waiting on a timer, a message, a task …). */
    public ProcessAssert isRunning() {
        return hasStatus(ProcessStatus.RUNNING);
    }

    // ---------------------------------------------------------------- steps

    /** Each of {@code stepIds} completed at least once. */
    public ProcessAssert hasPassedSteps(String... stepIds) {
        isNotNull();
        var executions = actual.stepExecutions();
        var missing = Arrays.stream(stepIds)
                .filter(stepId -> executions.stream().noneMatch(e -> stepId.equals(e.getStepId())
                        && e.getStatus() == StepExecutionStatus.COMPLETED))
                .toList();
        if (!missing.isEmpty()) {
            failWithMessage("Expected %s to have passed %s, but it has not passed %s.%n%s", actual,
                    List.of(stepIds), missing, describe());
        }
        return this;
    }

    /** None of {@code stepIds} completed. */
    public ProcessAssert hasNotPassedSteps(String... stepIds) {
        isNotNull();
        var executions = actual.stepExecutions();
        var passed = Arrays.stream(stepIds)
                .filter(stepId -> executions.stream().anyMatch(e -> stepId.equals(e.getStepId())
                        && e.getStatus() == StepExecutionStatus.COMPLETED))
                .toList();
        if (!passed.isEmpty()) {
            failWithMessage("Expected %s not to have passed %s, but it passed %s.%n%s", actual, List.of(stepIds),
                    passed, describe());
        }
        return this;
    }

    /**
     * The process is still going and {@code stepId} is in flight: a TIMER or a message wait not yet
     * over, a task its worker has not answered, a retry waiting out its backoff, a lock being waited for.
     */
    public ProcessAssert isWaitingAt(String stepId) {
        isNotNull();
        var waiting = actual.stepExecutions(stepId).stream()
                .anyMatch(e -> e.getStatus() != null && e.getStatus().keepsTheProcessInFlight());
        var status = actual.status();
        if (!waiting || (status != ProcessStatus.RUNNING && status != ProcessStatus.PAUSED)) {
            failWithMessage("Expected %s to be waiting at '%s'.%n%s", actual, stepId, describe());
        }
        return this;
    }

    /** The latest execution of {@code stepId} has {@code expected} status. */
    public ProcessAssert hasStepStatus(String stepId, StepExecutionStatus expected) {
        isNotNull();
        var latest = latest(stepId);
        if (latest == null || latest.getStatus() != expected) {
            failWithMessage("Expected step '%s' of %s to be %s but it is %s.%n%s", stepId, actual, expected,
                    latest == null ? "not started" : latest.getStatus(), describe());
        }
        return this;
    }

    /** The executions of {@code stepId}, for any AssertJ list assertion ({@code hasSize(3)} …). */
    public ListAssert<StepExecution> stepExecutions(String stepId) {
        isNotNull();
        return Assertions.assertThat(actual.stepExecutions(stepId))
                .as("executions of step '%s' of %s", stepId, actual);
    }

    // ---------------------------------------------------------------- variables

    /**
     * The variable is set to {@code expected}. Variables are text in the engine: a text expectation is
     * compared as text, anything else (a number, a map) as the JSON it would be stored as — so
     * {@code hasVariable("nights", 3)} matches a stored {@code 3}.
     */
    public ProcessAssert hasVariable(String name, Object expected) {
        isNotNull();
        var variables = actual.variables();
        if (!variables.containsKey(name)) {
            failWithMessage("Expected %s to have variable '%s' but it has only %s", actual, name, variables.keySet());
        }
        if (!Values.matches(variables.get(name), expected)) {
            failWithMessage("Expected variable '%s' of %s to be <%s> but it is <%s>", name, actual,
                    Values.text(expected), variables.get(name));
        }
        return this;
    }

    /** Every entry of {@code expected} is a variable with that value (see {@link #hasVariable}). */
    public ProcessAssert hasVariables(Map<String, ?> expected) {
        expected.forEach(this::hasVariable);
        return this;
    }

    public ProcessAssert hasVariable(String name) {
        isNotNull();
        if (!actual.variables().containsKey(name)) {
            failWithMessage("Expected %s to have variable '%s' but it has only %s", actual, name,
                    actual.variables().keySet());
        }
        return this;
    }

    public ProcessAssert doesNotHaveVariable(String name) {
        isNotNull();
        if (actual.variables().containsKey(name)) {
            failWithMessage("Expected %s not to have variable '%s' but it is <%s>", actual, name,
                    actual.variables().get(name));
        }
        return this;
    }

    // ---------------------------------------------------------------- reply

    /** The process answered (a REPLY step ran, or the engine answered for it). */
    public ProcessAssert hasReplied() {
        isNotNull();
        if (actual.reply() == null) {
            failWithMessage("Expected %s to have replied, but it has not.%n%s", actual, describe());
        }
        return this;
    }

    /** A REPLY step answered with this JSON document (compared as JSON, not as text). */
    public ProcessAssert hasReplied(String expectedJson) {
        hasReplied();
        var reply = actual.reply();
        if (reply.outcome() != ProcessReply.Outcome.REPLIED
                || !Values.tree(expectedJson).equals(reply.payload() == null ? null : Values.tree(reply.payload()))) {
            failWithMessage("Expected %s to have replied %s but it answered %s %s", actual, expectedJson,
                    reply.outcome(), reply.payload());
        }
        return this;
    }

    /** What the process answered had this outcome (REPLIED, FAILED, COMPENSATED …). */
    public ProcessAssert hasReplyOutcome(ProcessReply.Outcome expected) {
        hasReplied();
        if (actual.reply().outcome() != expected) {
            failWithMessage("Expected %s to have answered %s but it answered %s", actual, expected,
                    actual.reply().outcome());
        }
        return this;
    }

    // ---------------------------------------------------------------- updates

    /** The process applied these updates (COMPLETED), in this order — and no other. */
    public ProcessAssert hasAppliedUpdates(String... names) {
        isNotNull();
        var applied = actual.updates().stream()
                .filter(update -> update.status() == io.mateu.workflow.application.updates.ProcessUpdate.Status.COMPLETED)
                .map(io.mateu.workflow.application.updates.ProcessUpdate::name).toList();
        if (!applied.equals(Arrays.asList(names))) {
            failWithMessage("Expected %s to have applied the updates %s but it applied %s", actual,
                    Arrays.toString(names), applied);
        }
        return this;
    }

    // ---------------------------------------------------------------- events

    /** A PUBLISH_EVENT step of the process published an event of this CloudEvents type. */
    public ProcessAssert hasPublishedEvent(String eventType) {
        isNotNull();
        var types = actual.runtime().publishedEvents(actual).stream().map(e -> e.eventType()).toList();
        if (!types.contains(eventType)) {
            failWithMessage("Expected %s to have published a '%s' event but it published %s", actual, eventType, types);
        }
        return this;
    }

    // ---------------------------------------------------------------- compensation

    /**
     * The saga undid each of {@code stepIds}: the compensation each declares ({@code compensationStepId})
     * ran and completed. For a forEach step, its compensation ran over the iterations that completed — see
     * {@link IterationsAssert#hasCompensated} for which.
     */
    public ProcessAssert hasCompensated(String... stepIds) {
        isNotNull();
        var runs = compensationRuns();
        var missing = Arrays.stream(stepIds)
                .filter(stepId -> runs.stream().noneMatch(run -> stepId.equals(run.compensatedStepId()) && run.completed()))
                .toList();
        if (!missing.isEmpty()) {
            failWithMessage("Expected %s to have compensated %s, but it has not compensated %s.%n%s%n%s", actual,
                    List.of(stepIds), missing, describeCompensations(runs), describe());
        }
        return this;
    }

    /** None of {@code stepIds} was undone: no compensation of theirs completed. */
    public ProcessAssert hasNotCompensated(String... stepIds) {
        isNotNull();
        var runs = compensationRuns();
        var compensated = Arrays.stream(stepIds)
                .filter(stepId -> runs.stream().anyMatch(run -> stepId.equals(run.compensatedStepId()) && run.completed()))
                .toList();
        if (!compensated.isEmpty()) {
            failWithMessage("Expected %s not to have compensated %s, but it compensated %s.%n%s", actual,
                    List.of(stepIds), compensated, describeCompensations(runs));
        }
        return this;
    }

    /**
     * The saga undid exactly these steps, in this order — a rollback undoes the latest-completed step
     * first. Names the <b>compensated</b> steps (not their compensations); the order is the order their
     * compensations' tasks were dispatched.
     */
    public ProcessAssert hasCompensatedInOrder(String... stepIds) {
        isNotNull();
        var runs = compensationRuns();
        var order = runs.stream().filter(Executions.CompensationRun::completed)
                .map(Executions.CompensationRun::compensatedStepId).toList();
        if (!order.equals(Arrays.asList(stepIds))) {
            failWithMessage("Expected %s to have compensated %s, in this order, but it compensated %s.%n%s", actual,
                    List.of(stepIds), order, describeCompensations(runs));
        }
        return this;
    }

    /** No compensation ran at all. */
    public ProcessAssert hasNoCompensations() {
        isNotNull();
        var runs = compensationRuns();
        if (!runs.isEmpty()) {
            failWithMessage("Expected %s to have run no compensation, but it ran some.%n%s", actual,
                    describeCompensations(runs));
        }
        return this;
    }

    // ---------------------------------------------------------------- forEach

    /** The iterations of a forEach step, for assertions on each item: {@code iterations("rooms").hasOnlyFailed("Room 900")}. */
    public IterationsAssert iterations(String stepId) {
        isNotNull();
        return new IterationsAssert(actual, stepId);
    }

    // ---------------------------------------------------------------- escalation

    /** At least one of the USER_TASK's {@code escalation} levels fired. */
    public ProcessAssert hasEscalated(String stepId) {
        isNotNull();
        var task = escalatable(stepId);
        if (task.escalationsFired() == 0) {
            failWithMessage("Expected step '%s' of %s to have escalated, but no escalation fired.%n%s", stepId, actual,
                    describeEscalations(task));
        }
        return this;
    }

    /** Exactly {@code levels} of the USER_TASK's {@code escalation} levels fired (1 = the first). */
    public ProcessAssert hasEscalated(String stepId, int levels) {
        isNotNull();
        var task = escalatable(stepId);
        if (task.escalationsFired() != levels) {
            failWithMessage("Expected step '%s' of %s to have escalated %s time(s), but it escalated %s.%n%s", stepId,
                    actual, levels, task.escalationsFired(), describeEscalations(task));
        }
        return this;
    }

    /** None of the USER_TASK's escalation levels fired (yet). */
    public ProcessAssert hasNotEscalated(String stepId) {
        return hasEscalated(stepId, 0);
    }

    // ---------------------------------------------------------------- helpers

    private StepExecution latest(String stepId) {
        return actual.stepExecutions(stepId).stream()
                .max(Comparator.comparing((StepExecution e) -> e.getStartedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private String describe() {
        return Executions.describe(actual);
    }

    private List<Executions.CompensationRun> compensationRuns() {
        return Executions.compensationRuns(actual.stepExecutions(), actual.runtime().invocations(), actual.id());
    }

    private String describeCompensations(List<Executions.CompensationRun> runs) {
        if (runs.isEmpty()) {
            return "Compensations: none ran.";
        }
        var lines = new StringBuilder("Compensations, in the order they ran:");
        for (var run : runs) {
            lines.append(String.format("%n  %-24s undoing %-20s %s", run.compensationStepId(), run.compensatedStepId(),
                    run.execution().getStatus()));
        }
        return lines.toString();
    }

    /** The latest execution of {@code stepId}, which must be a USER_TASK with an escalation. */
    private StepExecution escalatable(String stepId) {
        var task = latest(stepId);
        if (task == null) {
            failWithMessage("Expected step '%s' of %s to be a USER_TASK that escalates, but it has not started.%n%s",
                    stepId, actual, describe());
        }
        var step = Executions.step(task);
        if (step == null || step.type() != io.mateu.workflow.domain.aggregates.StepType.USER_TASK
                || step.escalations() == null || step.escalations().isEmpty()) {
            failWithMessage("Step '%s' of %s declares no escalation (only a USER_TASK can escalate)", stepId, actual);
        }
        return task;
    }

    private static String describeEscalations(StepExecution task) {
        var levels = Executions.step(task).escalations();
        var lines = new StringBuilder("Escalation levels (task started " + task.getStartedAt() + ", status "
                + task.getStatus() + "):");
        for (var i = 0; i < levels.size(); i++) {
            var level = levels.get(i);
            lines.append(String.format("%n  %d. after %-8s %-10s %s", i + 1, level.after(), level.effectiveAction(),
                    i < task.escalationsFired() ? "fired" : "not fired"));
        }
        return lines.toString();
    }
}
