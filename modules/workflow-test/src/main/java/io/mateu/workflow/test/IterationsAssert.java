package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ForEach;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.assertj.core.api.AbstractAssert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Assertions on the iterations of a {@code forEach} step: how many ran, which completed, which failed,
 * which the saga undid — {@code assertThat(process).iterations("rooms")}.
 *
 * <p>An iteration is named by its 0-based <b>index</b> (an {@code Integer}), by its <b>label</b> (the
 * text the forEach's {@code label} made of its item, e.g. {@code "Room 102"}), or by its <b>item</b> (the
 * element itself: text, or a map compared as JSON). Failure messages name iterations the same way —
 * {@code #2 "Room 900"} — never by an execution id.
 *
 * <pre>{@code
 * assertThat(booking).iterations("rooms")
 *         .hasSize(3)
 *         .hasOnlyFailed("Room 900")
 *         .hasCompensated("Room 102", "Room 101");
 * }</pre>
 */
public class IterationsAssert extends AbstractAssert<IterationsAssert, TestProcess> {

    private final String stepId;

    IterationsAssert(TestProcess process, String stepId) {
        super(process, IterationsAssert.class);
        this.stepId = stepId;
    }

    /** It ran exactly {@code expected} iterations. */
    public IterationsAssert hasSize(int expected) {
        var view = view();
        if (view.iterations().size() != expected) {
            failWithMessage("Expected step '%s' of %s to have run %s iterations but it ran %s: %s", stepId, actual,
                    expected, view.iterations().size(), view.summary());
        }
        return this;
    }

    /** Every iteration completed. */
    public IterationsAssert allCompleted() {
        var view = view();
        var others = view.iterations().stream().filter(i -> i.getStatus() != StepExecutionStatus.COMPLETED).toList();
        if (others.isEmpty() && !view.iterations().isEmpty()) {
            return this;
        }
        failWithMessage("Expected every iteration of step '%s' of %s to have completed, but %s did not: %s", stepId,
                actual, view.names(others), view.summary());
        return this;
    }

    /** Each of these iterations completed (index, label or item; see the class comment). */
    public IterationsAssert hasCompleted(Object... iterations) {
        return each(iterations, i -> i.getStatus() == StepExecutionStatus.COMPLETED, "completed");
    }

    /** Each of these iterations failed (ended ERROR or TIMEOUT). */
    public IterationsAssert hasFailed(Object... iterations) {
        return each(iterations, i -> Executions.failed(i.getStatus()), "failed");
    }

    /** These iterations failed (ERROR or TIMEOUT), and no other did. */
    public IterationsAssert hasOnlyFailed(Object... iterations) {
        var view = view();
        var expected = view.resolveAll(iterations);
        var failed = view.iterations().stream().filter(i -> Executions.failed(i.getStatus())).toList();
        if (!new LinkedHashSet<>(failed).equals(new LinkedHashSet<>(expected))) {
            failWithMessage("Expected only %s of step '%s' of %s to have failed, but %s failed: %s", view.names(expected),
                    stepId, actual, failed.isEmpty() ? "none" : view.names(failed), view.summary());
        }
        return this;
    }

    /** No iteration failed. */
    public IterationsAssert hasNoFailures() {
        return hasOnlyFailed();
    }

    /**
     * The saga undid exactly these iterations — their compensation ran and completed — in any order;
     * no other iteration was undone.
     */
    public IterationsAssert hasCompensated(Object... iterations) {
        var view = view();
        var expected = view.resolveAll(iterations);
        var undone = view.compensated();
        if (!new LinkedHashSet<>(undone).equals(new LinkedHashSet<>(expected)) || undone.size() != expected.size()) {
            failWithMessage("Expected the compensation of step '%s' of %s to have undone %s, but it undid %s: %s", stepId,
                    actual, view.names(expected), undone.isEmpty() ? "none" : view.names(undone), view.summary());
        }
        return this;
    }

    /** The saga undid exactly these iterations, in this order (a rollback undoes the newest first). */
    public IterationsAssert hasCompensatedInOrder(Object... iterations) {
        var view = view();
        var expected = view.resolveAll(iterations);
        var undone = view.compensated();
        if (!undone.equals(expected)) {
            failWithMessage("Expected the compensation of step '%s' of %s to have undone %s in this order, but it undid %s",
                    stepId, actual, view.names(expected), undone.isEmpty() ? "none" : view.names(undone));
        }
        return this;
    }

    /** No iteration was undone. */
    public IterationsAssert hasNoneCompensated() {
        return hasCompensated();
    }

    /** One iteration — by index, label or item — for assertions of its own. */
    public IterationAssert iteration(Object iteration) {
        var view = view();
        return new IterationAssert(actual, stepId, view.resolve(iteration), view.forEach(), view.compensated());
    }

    // ---------------------------------------------------------------- helpers

    private IterationsAssert each(Object[] iterations, Predicate<StepExecution> condition, String what) {
        var view = view();
        var resolved = view.resolveAll(iterations);
        var not = resolved.stream().filter(condition.negate()).toList();
        if (!not.isEmpty()) {
            failWithMessage("Expected %s of step '%s' of %s to have %s, but %s did not: %s", view.names(resolved),
                    stepId, actual, what, view.names(not), view.summary());
        }
        return this;
    }

    private View view() {
        isNotNull();
        var executions = actual.stepExecutions();
        var container = Executions.container(executions, stepId);
        if (container == null) {
            var ran = executions.stream().anyMatch(e -> stepId.equals(e.getStepId()));
            failWithMessage(ran ? "Step '%s' of %s did not run as a forEach (it has no iterations).%n%s"
                    : "Step '%s' of %s has not run.%n%s", stepId, actual, Executions.describe(actual));
        }
        var iterations = Executions.iterationsOf(executions, container);
        var forEach = Executions.forEach(container);
        return new View(executions, iterations, forEach);
    }

    private final class View {
        private final List<StepExecution> executions;
        private final List<StepExecution> iterations;
        private final ForEach forEach;

        View(List<StepExecution> executions, List<StepExecution> iterations, ForEach forEach) {
            this.executions = executions;
            this.iterations = iterations;
            this.forEach = forEach;
        }

        List<StepExecution> iterations() {
            return iterations;
        }

        ForEach forEach() {
            return forEach;
        }

        List<StepExecution> compensated() {
            return Executions.compensatedIterations(executions, stepId, iterations);
        }

        List<StepExecution> resolveAll(Object[] references) {
            var resolved = new ArrayList<StepExecution>();
            for (var reference : references) {
                resolved.add(resolve(reference));
            }
            return resolved;
        }

        StepExecution resolve(Object reference) {
            StepExecution found = null;
            if (reference instanceof Integer index) {
                found = iterations.stream().filter(i -> index.equals(i.getItemIndex())).findFirst().orElse(null);
            } else if (reference instanceof CharSequence text) {
                found = iterations.stream().filter(i -> text.toString().equals(i.getItemLabel())).findFirst()
                        .orElseGet(() -> byItem(reference));
            } else if (reference != null) {
                found = byItem(reference);
            }
            if (found == null) {
                failWithMessage("Step '%s' of %s has no iteration %s; its iterations are %s", stepId, actual,
                        reference instanceof Integer ? "#" + reference : "<" + Values.text(reference) + ">", summary());
            }
            return found;
        }

        private StepExecution byItem(Object reference) {
            return iterations.stream().filter(i -> {
                var item = Executions.item(i, forEach);
                return item != null && Values.matches(item, reference);
            }).findFirst().orElse(null);
        }

        String names(List<StepExecution> list) {
            return list.stream().map(i -> Executions.name(i, forEach)).toList().toString();
        }

        String summary() {
            if (iterations.isEmpty()) {
                return "none";
            }
            var undone = compensated();
            var lines = new StringBuilder();
            for (var iteration : iterations) {
                lines.append(String.format("%n  %-28s %s%s", Executions.name(iteration, forEach), iteration.getStatus(),
                        undone.contains(iteration) ? " (compensated)" : ""));
                var output = iteration.output();
                if (!output.isEmpty()) {
                    lines.append(" ").append(output);
                }
            }
            return lines.toString();
        }
    }
}
