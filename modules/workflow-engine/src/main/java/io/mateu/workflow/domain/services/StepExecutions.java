package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.StepExecution;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Views over a process's step executions now that a step can have more than one: the passes of a
 * loop and the iterations of a forEach.
 *
 * <p>The <em>current</em> view — top-level executions that no later pass superseded — has exactly one
 * execution per step, as the whole engine had before, and is what the orchestrator, the process
 * status and the saga reason over. Iterations hang off their container and passes are history.
 */
public final class StepExecutions {

    /** Whether this is the step's execution in force: not an iteration, not a superseded pass. */
    public static boolean isCurrent(StepExecution execution) {
        return !execution.isSuperseded() && execution.getParentExecutionId() == null;
    }

    /** The current executions, one per step. */
    public static List<StepExecution> current(List<StepExecution> executions) {
        return executions.stream().filter(StepExecutions::isCurrent).toList();
    }

    /** The live (not superseded) iterations of a container, in item order. */
    public static List<StepExecution> iterationsOf(StepExecution container, List<StepExecution> executions) {
        return executions.stream()
                .filter(e -> container.getId().equals(e.getParentExecutionId()) && !e.isSuperseded())
                .sorted(Comparator.comparing(StepExecution::getItemIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** The execution of {@code stepId} that is in force, or null. */
    public static StepExecution currentOf(String stepId, List<StepExecution> executions) {
        return executions.stream()
                .filter(e -> isCurrent(e) && Objects.equals(stepId, e.getStepId()))
                .findFirst()
                .orElse(null);
    }

    private StepExecutions() {
    }
}
