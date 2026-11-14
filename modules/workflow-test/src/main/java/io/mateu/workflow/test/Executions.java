package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ForEach;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Reads what the assertions need off a process's step executions: the step each one ran, the
 * iterations of a forEach and how to name them, and which compensation undid what. Package-private:
 * the public face is {@link ProcessAssert}, {@link IterationsAssert} and {@link IterationAssert}.
 */
final class Executions {

    private Executions() {
    }

    /** The step an execution ran (its frozen copy), or null when it cannot be read. */
    static Step step(StepExecution execution) {
        if (execution == null || execution.getStepJson() == null) {
            return null;
        }
        try {
            return pojoFromJson(execution.getStepJson(), Step.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A failure that ends a run: ERROR or TIMEOUT. */
    static boolean failed(StepExecutionStatus status) {
        return status == StepExecutionStatus.ERROR || status == StepExecutionStatus.TIMEOUT;
    }

    // ---------------------------------------------------------------- forEach

    /**
     * The forEach container of {@code stepId} the assertions look at: its current one (the latest run
     * of the step that is not a compensation run), or null when the step never ran as a forEach.
     */
    static StepExecution container(List<StepExecution> executions, String stepId) {
        StepExecution found = null;
        for (var execution : executions) {
            if (stepId.equals(execution.getStepId()) && !execution.isIteration() && execution.isContainer()
                    && execution.compensatedStepId() == null) {
                if (found == null || !execution.isSuperseded() || found.isSuperseded()) {
                    found = execution;
                }
            }
        }
        return found;
    }

    /** The iterations of {@code container}, by index. */
    static List<StepExecution> iterationsOf(List<StepExecution> executions, StepExecution container) {
        if (container == null) {
            return List.of();
        }
        return executions.stream()
                .filter(execution -> container.getId().equals(execution.getParentExecutionId()))
                .sorted(Comparator.comparing(StepExecution::getItemIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** The forEach of the step a container (or one of its iterations) runs, or null. */
    static ForEach forEach(StepExecution execution) {
        var step = step(execution);
        return step == null ? null : step.forEach();
    }

    /** The item an iteration ran for, as the text the engine holds (JSON for a structured item), or null. */
    static String item(StepExecution iteration, ForEach forEach) {
        var scope = iteration.scope();
        if (forEach != null && scope.containsKey(forEach.itemVariable())) {
            return scope.get(forEach.itemVariable());
        }
        var index = forEach == null ? ForEach.DEFAULT_INDEX_AS : forEach.indexVariable();
        return scope.entrySet().stream().filter(entry -> !index.equals(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /**
     * How a failure message names an iteration: its index, and its label when the forEach has one
     * ({@code #2 "Room 900"}), else its item ({@code #2 room={"number":"900"}}) — never an execution id.
     */
    static String name(StepExecution iteration, ForEach forEach) {
        var index = iteration.getItemIndex() == null ? "?" : String.valueOf(iteration.getItemIndex());
        if (iteration.getItemLabel() != null && !iteration.getItemLabel().isBlank()) {
            return "#" + index + " \"" + iteration.getItemLabel() + "\"";
        }
        var item = item(iteration, forEach);
        if (item == null) {
            return "#" + index;
        }
        var variable = forEach != null ? forEach.itemVariable() : iteration.scope().keySet().stream()
                .filter(key -> !ForEach.DEFAULT_INDEX_AS.equals(key)).findFirst().orElse(ForEach.DEFAULT_AS);
        return "#" + index + " " + variable + "=" + abbreviated(item);
    }

    private static String abbreviated(String text) {
        var oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "...";
    }

    // ---------------------------------------------------------------- compensation

    /** For each compensation step, the steps it undoes (as their executions declare it). */
    static Map<String, List<String>> compensationSteps(List<StepExecution> executions) {
        var byCompensation = new LinkedHashMap<String, List<String>>();
        var seen = new java.util.HashSet<String>();
        for (var execution : executions) {
            if (!seen.add(execution.getStepId())) {
                continue;
            }
            var step = step(execution);
            if (step != null && step.compensable() && step.compensationStepId() != null
                    && !step.compensationStepId().isBlank()) {
                byCompensation.computeIfAbsent(step.compensationStepId(), id -> new ArrayList<>()).add(step.id());
            }
        }
        return byCompensation;
    }

    /** One run of a compensation: the compensation step, the step it undid, and how it ended. */
    record CompensationRun(String compensationStepId, String compensatedStepId, StepExecution execution) {
        boolean completed() {
            return execution.getStatus() == StepExecutionStatus.COMPLETED;
        }
    }

    /**
     * The compensations of a process that started, in the order they ran: by the first dispatch of their
     * task ({@code invocations}, in dispatch order), then — for a compensation that dispatches no task —
     * by when they started. The test clock does not move while the engine runs, so start times alone
     * would tie.
     */
    static List<CompensationRun> compensationRuns(List<StepExecution> executions, List<TaskInvocation> invocations,
                                                  String processId) {
        var compensations = compensationSteps(executions);
        var runs = new ArrayList<CompensationRun>();
        for (var execution : executions) {
            if (execution.isIteration() || execution.getStartedAt() == null
                    || !compensations.containsKey(execution.getStepId())) {
                continue;
            }
            var compensated = execution.compensatedStepId() != null ? execution.compensatedStepId()
                    : compensations.get(execution.getStepId()).getFirst();
            runs.add(new CompensationRun(execution.getStepId(), compensated, execution));
        }
        var firstDispatch = new LinkedHashMap<String, Integer>();
        for (var i = 0; i < invocations.size(); i++) {
            var invocation = invocations.get(i);
            if (Objects.equals(processId, invocation.processId())) {
                firstDispatch.putIfAbsent(invocation.stepId(), i);
            }
        }
        runs.sort(Comparator.comparing((CompensationRun run) -> firstDispatch.getOrDefault(run.compensationStepId(),
                        Integer.MAX_VALUE))
                .thenComparing(run -> run.execution().getStartedAt()));
        return runs;
    }

    /**
     * The iterations of a forEach that a compensation run undid (completed), in the order it undid them
     * (newest first). Read from the units the run recorded; for a run that did not record them, from the
     * index each compensation iteration saw.
     */
    static List<StepExecution> compensatedIterations(List<StepExecution> executions, String stepId,
                                                     List<StepExecution> iterations) {
        var undone = new ArrayList<StepExecution>();
        for (var run : executions) {
            if (run.isIteration() || !stepId.equals(run.compensatedStepId())) {
                continue;
            }
            var units = run.getScopeJson() == null ? null : run.scope().get("units");
            var unitIds = units == null || units.isBlank() ? List.<String>of() : List.of(units.split(","));
            var forEach = iterations.isEmpty() ? null : forEach(iterations.getFirst());
            var indexVariable = forEach == null ? ForEach.DEFAULT_INDEX_AS : forEach.indexVariable();
            for (var compensation : iterationsOf(executions, run)) {
                if (compensation.getStatus() != StepExecutionStatus.COMPLETED) {
                    continue;
                }
                StepExecution unit = null;
                var position = compensation.getItemIndex();
                if (position != null && position < unitIds.size()) {
                    var unitId = unitIds.get(position);
                    unit = iterations.stream().filter(i -> unitId.equals(i.getId())).findFirst().orElse(null);
                } else {
                    var index = compensation.scope().get(indexVariable);
                    unit = iterations.stream().filter(i -> String.valueOf(i.getItemIndex()).equals(index))
                            .findFirst().orElse(null);
                }
                if (unit != null) {
                    undone.add(unit);
                }
            }
        }
        return undone;
    }

    // ---------------------------------------------------------------- describing a process

    /**
     * One line per step execution for a failure message, a forEach's iterations under it in index order,
     * each named by index and label or item.
     */
    static String describe(TestProcess process) {
        var executions = process.stepExecutions();
        var lines = new StringBuilder("Steps:");
        var listed = new java.util.HashSet<String>();
        for (var execution : executions) {
            if (execution.isIteration()) {
                continue;
            }
            lines.append(String.format("%n  %-24s %s", execution.getStepId(), execution.getStatus()));
            var forEach = forEach(execution);
            for (var iteration : iterationsOf(executions, execution)) {
                listed.add(iteration.getId());
                lines.append(String.format("%n    %-22s %s", name(iteration, forEach), iteration.getStatus()));
            }
        }
        for (var execution : executions) {
            if (execution.isIteration() && !listed.contains(execution.getId())) {
                lines.append(String.format("%n  %-24s %s", execution.getStepId() + " " + name(execution, null),
                        execution.getStatus()));
            }
        }
        lines.append(String.format("%nVariables: %s", process.variables()));
        var problems = process.runtime().stubProblems();
        if (!problems.isEmpty()) {
            lines.append(String.format("%nStub problems:%n  %s", String.join(String.format("%n  "), problems)));
        }
        return lines.toString();
    }
}
