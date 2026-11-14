package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ForEach;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.assertj.core.api.AbstractAssert;

import java.util.List;

/**
 * Assertions on one iteration of a {@code forEach} step — {@code assertThat(process).iterations("rooms").iteration("Room 102")}.
 * A snapshot: the iteration as it was when {@link IterationsAssert#iteration(Object)} read it. Failure
 * messages name it by index and label or item ({@code #1 "Room 102"}).
 */
public class IterationAssert extends AbstractAssert<IterationAssert, StepExecution> {

    private final TestProcess process;
    private final String stepId;
    private final ForEach forEach;
    private final List<StepExecution> compensated;

    IterationAssert(TestProcess process, String stepId, StepExecution iteration, ForEach forEach,
                    List<StepExecution> compensated) {
        super(iteration, IterationAssert.class);
        this.process = process;
        this.stepId = stepId;
        this.forEach = forEach;
        this.compensated = compensated;
    }

    public IterationAssert hasStatus(StepExecutionStatus expected) {
        if (actual.getStatus() != expected) {
            failWithMessage("Expected iteration %s of step '%s' of %s to be %s but it is %s", name(), stepId, process,
                    expected, actual.getStatus());
        }
        return this;
    }

    public IterationAssert isCompleted() {
        return hasStatus(StepExecutionStatus.COMPLETED);
    }

    /** It ended ERROR or TIMEOUT. */
    public IterationAssert isFailed() {
        if (!Executions.failed(actual.getStatus())) {
            failWithMessage("Expected iteration %s of step '%s' of %s to have failed but it is %s", name(), stepId,
                    process, actual.getStatus());
        }
        return this;
    }

    /** It was cancelled before it finished (a failFast forEach stops the others). */
    public IterationAssert isCancelled() {
        return hasStatus(StepExecutionStatus.CANCELLED);
    }

    /** It reported {@code name} = {@code expected} (compared as {@link ProcessAssert#hasVariable} compares). */
    public IterationAssert hasOutput(String name, Object expected) {
        var output = actual.output();
        if (!output.containsKey(name) || !Values.matches(output.get(name), expected)) {
            failWithMessage("Expected iteration %s of step '%s' of %s to have reported %s=<%s> but it reported %s",
                    name(), stepId, process, name, Values.text(expected), output);
        }
        return this;
    }

    /** It ran for this item (text, or a map / list compared as JSON). */
    public IterationAssert hasItem(Object expected) {
        var item = Executions.item(actual, forEach);
        if (item == null || !Values.matches(item, expected)) {
            failWithMessage("Expected iteration %s of step '%s' of %s to have run for <%s> but it ran for <%s>", name(),
                    stepId, process, Values.text(expected), item);
        }
        return this;
    }

    /** The saga undid it: its compensation ran and completed. */
    public IterationAssert wasCompensated() {
        if (!compensated.contains(actual)) {
            failWithMessage("Expected iteration %s of step '%s' of %s to have been compensated, but it was not (%s)",
                    name(), stepId, process, actual.getStatus());
        }
        return this;
    }

    public IterationAssert wasNotCompensated() {
        if (compensated.contains(actual)) {
            failWithMessage("Expected iteration %s of step '%s' of %s not to have been compensated, but it was", name(),
                    stepId, process);
        }
        return this;
    }

    private String name() {
        return Executions.name(actual, forEach);
    }
}
