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

    // ---------------------------------------------------------------- helpers

    private StepExecution latest(String stepId) {
        return actual.stepExecutions(stepId).stream()
                .max(Comparator.comparing((StepExecution e) -> e.getStartedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private String describe() {
        var lines = new StringBuilder("Steps:");
        for (var execution : actual.stepExecutions()) {
            lines.append(String.format("%n  %-24s %s", execution.getStepId(), execution.getStatus()));
        }
        lines.append(String.format("%nVariables: %s", actual.variables()));
        return lines.toString();
    }
}
