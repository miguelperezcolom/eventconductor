package io.mateu.workflow.domain.services;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.workflow.application.services.JEXLEvaluator.eval;
import static io.mateu.workflow.application.services.JEXLEvaluator.evaluate;

/**
 * Domain Service that encapsulates the core business rules for workflow orchestration,
 * process step-over calculations, and process state transitions.
 *
 * <p>This class contains no references to persistence, database locks, or external messaging,
 * making it a pure, side-effect-free evaluator of process state transitions.
 */
@Service
@Slf4j
public class WorkflowOrchestrationService {

    @Value
    @lombok.AllArgsConstructor
    public static class TransitionResult {
        Process             updatedProcess;
        List<StepExecution> stepsToSave;
        boolean             processCompleted;
        boolean             processErrored;
        /**
         * This pass materialised new executions — a forEach's iterations, a loop's next pass — which
         * are saved CREATED and started by the next pass, never by this one. The caller runs that next
         * pass once these are saved: starting them here, before they exist in the store, would let a
         * synchronous worker's reply re-enter a step-over that cannot see them yet.
         */
        boolean             followUp;

        public TransitionResult(Process updatedProcess, List<StepExecution> stepsToSave,
                                boolean processCompleted, boolean processErrored) {
            this(updatedProcess, stepsToSave, processCompleted, processErrored, false);
        }
    }

    /** The most items one forEach may run over — a runaway guard; past it the step fails at start. */
    @org.springframework.beans.factory.annotation.Value("${workflow.foreach.max-items:1000}")
    int maxForEachItems = 1000;

    /**
     * The most times any loop may run a step or a section, whatever its {@code maxIterations} says —
     * the engine's own backstop against a definition that asked for too much.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.loops.max-iterations:1000}")
    int maxLoopIterations = 1000;

    public void setMaxForEachItems(int maxForEachItems) {
        this.maxForEachItems = maxForEachItems;
    }

    public void setMaxLoopIterations(int maxLoopIterations) {
        this.maxLoopIterations = maxLoopIterations;
    }

    /** What one pass decided, gathered as it goes: executions to save (by id, latest wins) and the process. */
    private static final class Pass {
        final java.util.LinkedHashMap<String, StepExecution> toSave = new java.util.LinkedHashMap<>();
        final java.util.Set<String> createdNow = new java.util.HashSet<>();
        final List<StepExecution> working;
        Process process;
        boolean followUp;

        Pass(Process process, List<StepExecution> executions) {
            this.process = process;
            this.working = new ArrayList<>(executions);
        }

        void save(StepExecution execution) {
            toSave.put(execution.getId(), execution);
        }

        void create(StepExecution execution) {
            working.add(execution);
            createdNow.add(execution.getId());
            toSave.put(execution.getId(), execution);
            followUp = true;
        }

        List<StepExecution> current() {
            return StepExecutions.current(working);
        }

        List<StepExecution> saved(List<StepExecution> more) {
            var all = new java.util.LinkedHashMap<>(toSave);
            more.forEach(execution -> all.put(execution.getId(), execution));
            return new ArrayList<>(all.values());
        }
    }

    /**
     * Computes the next state transitions for a process and its step executions.
     *
     * @param process        The current process state
     * @param stepExecutions The list of all step executions associated with this process
     * @return A TransitionResult containing the updated process, modified step executions, and metrics indicators
     */
    public TransitionResult calculateNextTransitions(Process process, List<StepExecution> stepExecutions) {
        if (ProcessStatus.CANCELLED.equals(process.getStatus())
                || ProcessStatus.PAUSED.equals(process.getStatus())
                || ProcessStatus.COMPENSATED.equals(process.getStatus())
                || ProcessStatus.COMPENSATION_FAILED.equals(process.getStatus())) {
            // A process being cancelled must not dispatch new steps. Same for a paused one:
            // in-flight steps may still complete (their reports are accepted), but their
            // successors are held here — and blocking-error handling is deferred — until
            // the process is resumed. COMPENSATED and COMPENSATION_FAILED are terminal saga
            // outcomes: return them untouched so the blocking-error branch below (the failed step
            // is still ERROR) can't flip them back to a plain ERROR.
            return new TransitionResult(process, List.of(), false, false);
        }

        // Parse each step's JSON at most once per call: getStep is hit 2-3× per candidate
        // (eligibility, END detection, END transition) and pojoFromJson is not free.
        var stepCache = new HashMap<String, Step>();
        var pass = new Pass(process, stepExecutions);

        // A process whose flow is blocked by a failed step stops launching forEach iterations (its
        // compensations aside); see advanceContainer. Read off the steps, not the process status: a
        // step an operator is retrying leaves the process ERROR for a moment with nothing blocking.
        boolean failed = pass.current().stream().anyMatch(se -> isBlockingError(se, stepCache));

        // forEach containers first: their iterations are driven here, never by the precondition graph.
        for (var container : pass.current()) {
            if (StepExecutionStatus.PENDING.equals(container.getStatus()) && container.isContainer()) {
                advanceContainer(container, pass, failed, stepCache);
            }
        }
        // Then the loops: each step that completed and may send the flow round again is decided, once.
        if (!failed) {
            for (var execution : pass.current()) {
                if (StepExecutionStatus.COMPLETED.equals(execution.getStatus()) && execution.getLoopDecision() == null) {
                    var step = getStep(execution, stepCache);
                    if (step.loops()) {
                        decideLoop(execution, step, pass, stepCache);
                    }
                }
            }
        }

        // From here on the flow is judged on the CURRENT executions — one per step — exactly as it
        // always was. A loop that went round has superseded its section's executions with fresh
        // CREATED ones, so every barrier, latch and guard in it starts over without knowing why.
        var current = pass.current();
        for (StepExecution stepExecution : current) {
            if (isBlockingError(stepExecution, stepCache)) {
                // A step that failed blocks the flow: don't schedule successors or complete the process.
                Process updatedProcess = pass.process;
                boolean processErrored = false;
                if (pass.process.getStatus() != ProcessStatus.ERROR) {
                    updatedProcess = pass.process.withStatus(ProcessStatus.ERROR);
                    processErrored = true;
                }
                return new TransitionResult(updatedProcess, pass.saved(List.of()), false, processErrored, pass.followUp);
            }
        }

        // Pure dataflow: every CREATED step whose preconditions are ALL satisfied (and whose
        // guard expression is truthy) starts now, concurrently. There is no ordering between
        // steps beyond the precondition graph — active steps never block unrelated branches.
        // What this pass itself created waits for the next one (see TransitionResult.followUp).
        var processNow = pass.process;
        List<StepExecution> executableSteps = current.stream()
                .filter(this::isCreated)
                .filter(stepExecution -> !pass.createdNow.contains(stepExecution.getId()))
                .filter(stepExecution -> shouldRunStep(stepExecution, processNow, current, stepCache))
                .toList();

        boolean hasEndStep = executableSteps.stream()
                .anyMatch(stepExecution -> StepType.END.equals(getStep(stepExecution, stepCache).type()));

        if (hasEndStep) {
            return handleEndStepTransition(pass, current, executableSteps, stepCache);
        }

        return handleStandardOrImplicitCompletionTransition(pass, current, executableSteps, stepCache);
    }

    // ------------------------------------------------------------------------------- forEach

    /**
     * Starts a step that runs once per item: evaluates {@code items} once, materialises one CREATED
     * iteration per element and leaves the container PENDING. An empty list completes it at once; a
     * list that cannot be evaluated, or is longer than {@code workflow.foreach.max-items}, fails it.
     *
     * <p>A container that already has iterations is being run again (an operator retried the failed
     * step): the completed iterations stand, and each failed or cancelled one is replaced by a fresh
     * one — with a fresh retry budget — for the same item.
     */
    private void startContainer(StepExecution container, Step step, Pass pass) {
        var forEach = step.forEach();
        var existing = StepExecutions.iterationsOf(container, pass.working);
        if (!existing.isEmpty()) {
            int total = Math.max(container.totalIterations(), existing.size());
            for (var iteration : existing) {
                var status = iteration.getStatus();
                if (status == StepExecutionStatus.ERROR || status == StepExecutionStatus.TIMEOUT
                        || status == StepExecutionStatus.CANCELLED) {
                    iteration.supersede(null);
                    pass.save(iteration);
                    pass.create(StepExecution.iterationOf(container, iteration.getStepJson(),
                            iteration.getItemIndex() == null ? 0 : iteration.getItemIndex(), iteration.scope()));
                }
            }
            container.beginIterations(pass.process, total, container.compensatedStepId());
            pass.save(container);
            pass.followUp = true;
            return;
        }
        List<Object> items;
        try {
            items = MultiInstance.items(forEach, pass.process);
        } catch (MultiInstance.ItemsException e) {
            container.beginIterations(pass.process, 0, null);
            container.failIterations(StepExecutionStatus.ERROR, "Step " + step.id() + ": " + e.getMessage());
            pass.save(container);
            return;
        }
        if (items.size() > maxForEachItems) {
            container.beginIterations(pass.process, 0, null);
            container.failIterations(StepExecutionStatus.ERROR, "Step " + step.id() + ": forEach items gave "
                    + items.size() + " elements, over the limit of " + maxForEachItems
                    + " (workflow.foreach.max-items).");
            pass.save(container);
            return;
        }
        container.beginIterations(pass.process, items.size(), null);
        pass.save(container);
        if (items.isEmpty()) {
            completeContainer(container, forEach, List.of(), 0, pass,
                    "forEach on step " + step.id() + " had no items: completed at once.");
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            pass.create(StepExecution.iterationOf(container, container.getStepJson(), i,
                    MultiInstance.scopeFor(forEach, items.get(i), i)));
        }
    }

    /**
     * Moves a forEach container on: fails it when an iteration failed for good, completes it when its
     * {@code completion} is reached, and otherwise starts CREATED iterations, in item order, while
     * fewer than its concurrency are in flight.
     *
     * <p>In a process that has failed, a container that is not a compensation stops: what has not
     * started is cancelled, what is at a worker is sent a cancellation, and the container ends
     * CANCELLED — the saga then compensates the iterations that did complete.
     */
    private void advanceContainer(StepExecution container, Pass pass, boolean processFailed, Map<String, Step> cache) {
        int total = container.totalIterations();
        if (total < 0) {
            return;
        }
        var iterations = StepExecutions.iterationsOf(container, pass.working);
        if (iterations.size() < total) {
            // Not all of them are visible to this pass yet — a step-over re-entered while the pass that
            // materialised them is still saving. That pass's follow-up will see them all.
            return;
        }
        var step = getStep(container, cache);
        boolean compensation = container.compensatedStepId() != null;
        var forEach = compensation || step.forEach() == null ? ForEach.compensation() : step.forEach();
        if (processFailed && !compensation) {
            cancelIterations(iterations, pass);
            container.updateStatus(StepExecutionStatus.CANCELLED);
            container.logInfo("forEach on step " + container.getStepId() + " stopped: the process failed.");
            pass.save(container);
            return;
        }
        var failedIteration = iterations.stream()
                .filter(i -> i.getStatus() == StepExecutionStatus.ERROR || i.getStatus() == StepExecutionStatus.TIMEOUT)
                .findFirst().orElse(null);
        if (failedIteration != null) {
            cancelIterations(iterations, pass);
            container.failIterations(failedIteration.getStatus(), "Iteration " + failedIteration.getItemIndex()
                    + " of step " + container.getStepId() + " ended " + failedIteration.getStatus()
                    + (compensation ? "; the compensation cannot go on." : "; the step fails."));
            pass.save(container);
            return;
        }
        int completed = (int) iterations.stream().filter(i -> i.getStatus() == StepExecutionStatus.COMPLETED).count();
        int active = (int) iterations.stream().filter(i -> i.getStatus().keepsTheProcessInFlight()).count();
        String why = null;
        if (completed == total) {
            why = "forEach on step " + container.getStepId() + " completed: all " + total + " iterations.";
        } else if (completed > 0 && forEach.completesOnAny()) {
            why = "forEach on step " + container.getStepId() + " completed with its first completed iteration (completion: any).";
        } else if (completed > 0 && forEach.completionCondition() != null
                && MultiInstance.conditionHolds(forEach, pass.process, iterations, total, completed, active)) {
            why = "forEach on step " + container.getStepId() + " completed after " + completed + " of " + total
                    + " iterations: '" + forEach.completionCondition() + "' holds.";
        }
        if (why != null) {
            cancelIterations(iterations, pass);
            completeContainer(container, forEach, iterations, total, pass, why);
            return;
        }
        int inFlight = active;
        for (var iteration : iterations) {
            if (inFlight >= forEach.concurrency()) {
                break;
            }
            if (StepExecutionStatus.CREATED.equals(iteration.getStatus()) && !pass.createdNow.contains(iteration.getId())) {
                iteration.start(pass.process);
                pass.save(iteration);
                inFlight++;
            }
        }
    }

    private void completeContainer(StepExecution container, ForEach forEach, List<StepExecution> iterations,
                                   int total, Pass pass, String why) {
        String output = null;
        if (forEach.hasOutput()) {
            output = MultiInstance.output(forEach, iterations, total);
            pass.process = withVariable(pass.process, forEach.output(), output);
        }
        container.completeIterations(output, why);
        pass.save(container);
    }

    /** Cancels every iteration that has not finished: a cancellation to the worker for those at one. */
    private void cancelIterations(List<StepExecution> iterations, Pass pass) {
        for (var iteration : iterations) {
            var status = iteration.getStatus();
            if (status == StepExecutionStatus.CREATED) {
                iteration.cancelQuietly();
                pass.save(iteration);
            } else if (status.keepsTheProcessInFlight()) {
                iteration.updateStatus(StepExecutionStatus.CANCELLED);
                pass.save(iteration);
            }
        }
    }

    private static Process withVariable(Process process, String name, String value) {
        var variables = new ArrayList<Variable>();
        process.getVariables().stream().filter(v -> !name.equals(v.name())).forEach(variables::add);
        variables.add(new Variable(name, value));
        return process.withVariables(variables);
    }

    // --------------------------------------------------------------------------------- loops

    /**
     * Decides, once, whether a step that just completed sends the flow round again, and latches the
     * answer on it. {@code repeat}: runs the step again while {@code until} is false. {@code loopBack}:
     * runs the section from {@code to} to here again while {@code while} holds. Going round past a cap
     * — the loop's {@code maxIterations}, a step's {@code maxSuccessfulExecutions} (or the workflow's
     * {@code defaultMaxStepExecutions}), or {@code workflow.loops.max-iterations} — fails the step.
     */
    private void decideLoop(StepExecution execution, Step step, Pass pass, Map<String, Step> cache) {
        int runs = runsSoFar(execution, pass.working);
        var context = expressionContext(step, pass.process);
        context.put("iteration", runs);
        boolean again;
        String condition;
        if (step.repeat() != null) {
            condition = step.repeat().until();
            again = !evaluateLoopCondition(condition, context, step, pass.process);
        } else {
            condition = step.loopBack().condition();
            again = step.loopBack().unconditional() || evaluateLoopCondition(condition, context, step, pass.process);
        }
        if (!again) {
            execution.decideLoop(StepExecution.LOOP_EXITED);
            pass.save(execution);
            return;
        }
        int declaredMax = step.repeat() != null ? step.repeat().maxIterations() : step.loopBack().maxIterations();
        String exhausted = null;
        if (declaredMax > 0 && runs >= declaredMax) {
            exhausted = "maxIterations " + declaredMax;
        } else if (runs >= maxLoopIterations) {
            exhausted = "the engine's limit of " + maxLoopIterations + " (workflow.loops.max-iterations)";
        }
        List<String> section = step.repeat() != null ? List.of(step.id()) : sectionOf(step, pass);
        if (exhausted == null) {
            exhausted = capReached(section, pass, cache);
        }
        if (exhausted != null) {
            execution.exhaustLoop("Step " + step.id() + " would run again"
                    + (condition == null || condition.isBlank() ? "" : " ('" + condition + "' "
                    + (step.repeat() != null ? "is still false" : "still holds") + ")")
                    + " after " + runs + (runs == 1 ? " run" : " runs") + ", past " + exhausted + ": it fails.");
            pass.save(execution);
            return;
        }
        var wakeAt = delayOf(step) > 0 ? EngineClock.now().plus(java.time.Duration.ofMillis(delayOf(step))) : null;
        if (step.repeat() != null) {
            execution.supersede(StepExecution.LOOP_LOOPED);
            execution.logInfo("Step " + step.id() + " runs again (run " + (runs + 1) + "): '" + condition + "' is false.");
            pass.save(execution);
            var next = StepExecution.nextPassOf(execution);
            if (wakeAt != null) {
                next.parkUntil(wakeAt);
            }
            pass.create(next);
            return;
        }
        var to = step.loopBack().to();
        execution.logInfo("Step " + step.id() + " loops back to " + to + " (pass " + (runs + 1) + ")"
                + (condition == null || condition.isBlank() ? "." : ": '" + condition + "' holds."));
        for (var member : section) {
            var currentExecution = StepExecutions.currentOf(member, pass.working);
            if (currentExecution == null) {
                continue;
            }
            reopen(currentExecution, member.equals(step.id()), member.equals(to) ? wakeAt : null, pass);
        }
    }

    /** Supersedes one section step's current execution with a fresh one, stopping whatever it had going. */
    private void reopen(StepExecution execution, boolean isTheLoopingStep, LocalDateTime wakeAt, Pass pass) {
        var status = execution.getStatus();
        if (status == StepExecutionStatus.CREATED) {
            execution.cancelQuietly();
        } else if (status.keepsTheProcessInFlight()) {
            execution.updateStatus(StepExecutionStatus.CANCELLED);
            if (execution.isContainer()) {
                cancelIterations(StepExecutions.iterationsOf(execution, pass.working), pass);
            }
        }
        execution.supersede(isTheLoopingStep ? StepExecution.LOOP_LOOPED : null);
        pass.save(execution);
        var next = StepExecution.nextPassOf(execution);
        if (wakeAt != null) {
            next.parkUntil(wakeAt);
        }
        pass.create(next);
    }

    /**
     * How many times this execution's loop has run, this run included: 1 + the unbroken chain of
     * earlier passes of the same step that went round ({@code LOOPED}). A pass that exited, or one a
     * loop around it superseded, starts a fresh count — a repeat nested in a section counts per pass
     * of the section.
     */
    private int runsSoFar(StepExecution execution, List<StepExecution> executions) {
        int runs = 1;
        int iteration = execution.getIteration();
        while (true) {
            int previous = iteration - 1;
            var before = executions.stream()
                    .filter(e -> e.getParentExecutionId() == null && execution.getStepId().equals(e.getStepId())
                            && e.getIteration() == previous)
                    .findFirst().orElse(null);
            if (before == null || !StepExecution.LOOP_LOOPED.equals(before.getLoopDecision())) {
                return runs;
            }
            runs++;
            iteration = previous;
        }
    }

    /**
     * The first step of {@code section} that has already completed as many times as it may — its own
     * {@code maxSuccessfulExecutions}, or else the workflow's {@code defaultMaxStepExecutions} — said
     * as the reason, or null when every step may run again.
     */
    private String capReached(List<String> section, Pass pass, Map<String, Step> cache) {
        int workflowDefault = defaultMaxStepExecutions(pass.process);
        for (var stepId : section) {
            var current = StepExecutions.currentOf(stepId, pass.working);
            if (current == null) {
                continue;
            }
            var step = getStep(current, cache);
            int cap = step.maxSuccessfulExecutions() > 0 ? step.maxSuccessfulExecutions() : workflowDefault;
            if (cap <= 0) {
                continue;
            }
            long successes = pass.working.stream()
                    .filter(e -> e.getParentExecutionId() == null && stepId.equals(e.getStepId())
                            && StepExecutionStatus.COMPLETED.equals(e.getStatus())
                            && !StepExecution.LOOP_DISCARDED.equals(e.getLoopDecision()))
                    .count();
            if (successes >= cap) {
                return (step.maxSuccessfulExecutions() > 0 ? "step " + stepId + "'s maxSuccessfulExecutions "
                        : "the workflow's defaultMaxStepExecutions ") + cap;
            }
        }
        return null;
    }

    private static int defaultMaxStepExecutions(Process process) {
        var json = process.getWorkflowDefinitionJson();
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            return pojoFromJson(json, WorkflowDefinition.class).defaultMaxStepExecutions();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The steps a loopBack re-opens, from the steps this process actually has (injected ones included). */
    private List<String> sectionOf(Step step, Pass pass) {
        var nodes = new ArrayList<io.mateu.workflow.analysis.LoopRules.Node>();
        var known = new HashMap<String, Object>();
        for (var execution : pass.current()) {
            Step s;
            try {
                s = pojoFromJson(execution.getStepJson(), Step.class);
            } catch (RuntimeException e) {
                continue;
            }
            known.put(s.id(), s);
            nodes.add(new io.mateu.workflow.analysis.LoopRules.Node(s.id(), s.type() == null ? null : s.type().name(),
                    s.preconditionIds(), null, null, null, null));
        }
        var successors = io.mateu.workflow.analysis.LoopRules.successorsOf(nodes);
        return new ArrayList<>(io.mateu.workflow.analysis.LoopRules.section(step.loopBack().to(), step.id(), successors, known));
    }

    private static long delayOf(Step step) {
        return step.repeat() != null ? step.repeat().delay() : step.loopBack().delay();
    }

    /** A loop condition. Fail-closed like a guard: one that cannot be evaluated reads false. */
    private boolean evaluateLoopCondition(String expression, Map<String, Object> context, Step step, Process process) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try {
            var evaluation = evaluate(expression, context);
            reportUndefined(evaluation, expression, "the loop of step " + step.id(), process);
            return isTruthy(evaluation.value());
        } catch (Exception e) {
            log.error("Error evaluating the loop condition '{}' of step {}; read as false", expression, step.id(), e);
            return false;
        }
    }

    private boolean isBlockingError(StepExecution stepExecution, Map<String, Step> cache) {
        if (StepExecutionStatus.ERROR.equals(stepExecution.getStatus())) {
            return true;
        }
        if (StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus())) {
            // A timeout the step routes to an on-timeout step is handled, not a failure: flow moves
            // to that step (see shouldRunStep) instead of blocking the process.
            var step = getStep(stepExecution, cache);
            return step.onTimeoutStepId() == null || step.onTimeoutStepId().isBlank();
        }
        return false;
    }

    private boolean isCreated(StepExecution stepExecution) {
        return StepExecutionStatus.CREATED.equals(stepExecution.getStatus());
    }

    private Step getStep(StepExecution stepExecution, Map<String, Step> cache) {
        return cache.computeIfAbsent(stepExecution.id(),
                k -> pojoFromJson(stepExecution.getStepJson(), Step.class));
    }

    private boolean shouldRunStep(StepExecution stepExecution, Process process, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        Step step = getStep(stepExecution, cache);
        // A step reached by another step's on-timeout branch runs regardless of its normal
        // preconditions (its predecessor timed out rather than completing, so those are never met).
        if (isOnTimeoutTargetTriggered(step, stepExecutions, cache)) {
            return true;
        }
        // One question, asked once: a step-level preconditionExpression has already been folded
        // into the step's links by resolvedPreconditions(), so there is nothing left to evaluate
        // separately except on a step that has no links at all.
        return checkPreconditionStep(step, process, stepExecutions, cache);
    }

    /** True when some step that names {@code step} as its {@code onTimeoutStepId} has timed out. */
    private boolean isOnTimeoutTargetTriggered(Step step, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        return stepExecutions.stream()
                .filter(se -> StepExecutionStatus.TIMEOUT.equals(se.getStatus()))
                .map(se -> getStep(se, cache))
                .anyMatch(source -> step.id().equals(source.onTimeoutStepId()));
    }

    private boolean checkPreconditionStep(Step step, Process process, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        if (step.resolvedPreconditions().isEmpty()) {
            // Nothing to wait for, so nothing to fold a step-level guard into: here — and only
            // here — that expression is still read as the step's own gate.
            return isAnEntryPoint(step) && evaluatePreconditionExpression(step, process);
        }

        // A CHOICE is an exclusive split: of its successors it takes exactly one. A successor of a
        // CHOICE therefore runs only if it is the branch that CHOICE picks — and the pick latches, so
        // once a sibling has left the starting gate a variable changing later cannot hand the branch
        // to another. See pickedChoiceBranch for the ordering.
        var choiceLink = choiceLinkInto(step, stepExecutions, cache);
        if (choiceLink != null) {
            return isPickedChoiceBranch(step, choiceLink.stepId(), process, stepExecutions, cache);
        }
        // A precondition is satisfied once its step has a COMPLETED execution AND its own guard,
        // if it declares one, holds. An XOR join proceeds as soon as ANY incoming branch is
        // satisfied; every other step — including an AND join, the default barrier — needs them ALL.
        //
        // A guard that is false leaves its link unsatisfied, which on an AND join means the step
        // waits: "wait for all of them" is read literally, and a branch whose condition never comes
        // true holds the step forever. That is a workflow that cannot proceed, and it is meant to
        // be — the alternative, quietly dropping the branch, lets a step run having waited for less
        // than its author wrote. A guard reads process variables, so one that is false now becomes
        // true later if the variable changes, and the step is released then.
        java.util.function.Predicate<Precondition> satisfied = precondition -> {
            boolean completed = stepExecutions.stream()
                    .filter(se -> precondition.stepId().equals(se.getStepId()))
                    .anyMatch(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus()));
            return completed && evaluateGuard(precondition, step, process);
        };
        boolean xorJoin = step.type() == StepType.JOIN && step.joinType() == JoinType.XOR;
        // An END is reached, not joined. One arriving branch is the whole of its condition.
        //
        // Draw two exclusive routes — confirm on paid, cancel on unpaid — and let both point at
        // the same END, which is the obvious way to draw it. Under allMatch that END waits for
        // BOTH, and one of them is a route the flow did not take and never will: its guard read a
        // variable that already has the other value. The process sits at 60% for ever, RUNNING,
        // with the last step CREATED and the untaken branch CREATED beside it. Nothing is broken
        // enough to report — it is simply waiting for something that cannot happen.
        //
        // Treating END as a join is also the wrong shape on its own terms: a JOIN says "gather
        // these branches", an END says "the process is over". Whatever else was pending stops
        // mattering the moment one branch reaches it, which is exactly what the engine already
        // does to a branch still at a worker when another reaches END.
        boolean anyBranchIsEnough = xorJoin || step.type() == StepType.END;
        return anyBranchIsEnough
                ? step.resolvedPreconditions().stream().anyMatch(satisfied)
                : step.resolvedPreconditions().stream().allMatch(satisfied);
    }

    /** The incoming link of {@code step} that comes from a CHOICE, or null when none does. */
    private Precondition choiceLinkInto(Step step, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        return step.resolvedPreconditions().stream()
                .filter(precondition -> {
                    Step predecessor = stepById(precondition.stepId(), stepExecutions, cache);
                    return predecessor != null && predecessor.type() == StepType.CHOICE;
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether {@code step} is the one branch its CHOICE takes.
     *
     * <p>The CHOICE must have completed. Then, among its successors whose guard on the CHOICE link
     * holds right now, the winner is the one with the LONGEST guard expression — most specific first,
     * evaluated down to the shortest, so an unguarded successor (length 0) is the else branch, tried
     * last. Ties break on step id for a stable, deterministic pick.
     *
     * <p>The pick latches: if any sibling has already left the starting gate (any status past
     * CREATED that is not a plain CANCELLED), this branch cannot start, so the split stays exclusive
     * even if the variables a guard reads change after the CHOICE completed.
     */
    private boolean isPickedChoiceBranch(Step step, String choiceId, Process process,
                                         List<StepExecution> stepExecutions, Map<String, Step> cache) {
        boolean choiceCompleted = stepExecutions.stream()
                .filter(se -> choiceId.equals(se.getStepId()))
                .anyMatch(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus()));
        if (!choiceCompleted) {
            return false;
        }

        List<String> successorIds = stepExecutions.stream()
                .map(se -> getStep(se, cache))
                .filter(candidate -> candidate.resolvedPreconditions().stream()
                        .anyMatch(p -> choiceId.equals(p.stepId())))
                .map(Step::id)
                .distinct()
                .toList();

        boolean aSiblingAlreadyTaken = successorIds.stream()
                .filter(id -> !id.equals(step.id()))
                .anyMatch(id -> hasLeftTheStartingGate(id, stepExecutions));
        if (aSiblingAlreadyTaken) {
            return false;
        }

        String winner = successorIds.stream()
                .filter(id -> choiceGuardHolds(choiceId, id, process, stepExecutions, cache))
                .min(Comparator
                        .comparingInt((String id) -> -choiceGuardLength(choiceId, id, stepExecutions, cache))
                        .thenComparing(Comparator.naturalOrder()))
                .orElse(null);
        return step.id().equals(winner);
    }

    /** The precondition on successor {@code stepId} that links it back to {@code choiceId}. */
    private Precondition choiceLinkOf(String choiceId, String stepId, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        Step step = stepById(stepId, stepExecutions, cache);
        if (step == null) {
            return null;
        }
        return step.resolvedPreconditions().stream()
                .filter(p -> choiceId.equals(p.stepId()))
                .findFirst()
                .orElse(null);
    }

    private boolean choiceGuardHolds(String choiceId, String stepId, Process process,
                                     List<StepExecution> stepExecutions, Map<String, Step> cache) {
        Step step = stepById(stepId, stepExecutions, cache);
        Precondition link = choiceLinkOf(choiceId, stepId, stepExecutions, cache);
        return step != null && link != null && evaluateGuard(link, step, process);
    }

    private int choiceGuardLength(String choiceId, String stepId, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        Precondition link = choiceLinkOf(choiceId, stepId, stepExecutions, cache);
        return link != null && link.hasGuard() ? link.expression().length() : 0;
    }

    private boolean hasLeftTheStartingGate(String stepId, List<StepExecution> stepExecutions) {
        return stepExecutions.stream()
                .filter(se -> stepId.equals(se.getStepId()))
                .anyMatch(se -> !StepExecutionStatus.CREATED.equals(se.getStatus())
                        && !StepExecutionStatus.CANCELLED.equals(se.getStatus()));
    }

    /** Any step definition carrying {@code stepId}; all executions of a step share one definition. */
    private Step stepById(String stepId, List<StepExecution> stepExecutions, Map<String, Step> cache) {
        return stepExecutions.stream()
                .filter(se -> stepId.equals(se.getStepId()))
                .findFirst()
                .map(se -> getStep(se, cache))
                .orElse(null);
    }

    /**
     * Whether a step with nothing to wait for may nevertheless run.
     *
     * <p>Only a flow's entry points may: START, and a WAIT_FOR_MESSAGE that begins a flow rather
     * than sitting inside one — that one has to be armed when the process is created or the
     * message it waits for finds nothing to correlate with.
     *
     * <p>Everything else with no incoming link is a step that some other mechanism starts, or a
     * mistake, and running it at process creation serves neither. The mechanism this exists for
     * is compensation: a compensation is declared on the step it undoes, and the rollback
     * pipeline starts it directly, so it needs no way in of its own. Reading "no preconditions"
     * as "run immediately" is what forced compensation steps to be anchored to some unrelated
     * step with a permanently false guard — a fiction that had to be written correctly every
     * time, and that silently turned into a live branch of the happy path when it was not.
     */
    private boolean isAnEntryPoint(Step step) {
        return StepType.START.equals(step.type()) || StepType.WAIT_FOR_MESSAGE.equals(step.type());
    }

    /** A link's own condition. Fail-closed, exactly like the step-level one. */
    private boolean evaluateGuard(Precondition precondition, Step step, Process process) {
        if (!precondition.hasGuard()) {
            return true;
        }
        try {
            var evaluation = evaluate(precondition.expression(), expressionContext(step, process));
            reportUndefined(evaluation, precondition.expression(),
                    "the link " + precondition.stepId() + " -> " + step.id(), process);
            return isTruthy(evaluation.value());
        } catch (Exception e) {
            log.error("Error evaluating the guard '{}' on the link {} -> {}, the link will not be "
                            + "satisfied", precondition.expression(), precondition.stepId(), step.id(), e);
            return false;
        }
    }

    /**
     * The step-level gate, for the one step that has no link to fold it into: an entry point.
     * Everywhere else {@link Step#resolvedPreconditions()} has already ANDed it onto the links,
     * and it is {@link #evaluateGuard} that reads it.
     */
    private boolean evaluatePreconditionExpression(Step step, Process process) {
        if (step.preconditionExpression() == null || step.preconditionExpression().isEmpty()) {
            return true;
        }
        try {
            var evaluation = evaluate(step.preconditionExpression(), expressionContext(step, process));
            reportUndefined(evaluation, step.preconditionExpression(),
                    "the precondition of step " + step.id(), process);
            return isTruthy(evaluation.value());
        } catch (Exception e) {
            // Fail closed: a guard that cannot be evaluated must not let the step run.
            log.error("Error evaluating precondition expression '{}' for step {}, step will not run",
                    step.preconditionExpression(), step.id(), e);
            return false;
        }
    }

    /** The JEXL context both kinds of guard are evaluated in, so they read the same world. */
    private Map<String, Object> expressionContext(Step step, Process process) {
        var variables = new HashMap<String, Object>();
        variables.put("process", process);
        variables.put("step", step);
        process.getVariables().forEach(variable -> variables.put(variable.name(), variable.value()));
        // Seeded AFTER the variables so the canonical value always wins: JEXL runs with
        // RESTRICTED permissions (no introspection of domain classes), so businessKey must
        // be available as a plain context variable — `process.businessKey` cannot evaluate.
        variables.put("businessKey", process.getBusinessKey());
        return variables;
    }

    /**
     * Whether a guard's result lets the link through.
     *
     * <p>Close to JavaScript, with one deliberate departure that must not be "corrected": the
     * string {@code "false"} is falsy here and truthy in JavaScript. Every process variable in this
     * engine is a {@code (name, value)} pair of <em>strings</em>, so a variable holding {@code
     * "false"} is what a guard written as {@code !approved} reads — treating it as truthy the way
     * JavaScript does would silently invert every negated guard in every deployed definition.
     *
     * <p>Numbers follow JavaScript: {@code 0} is falsy, anything else is not. Before, every
     * non-Boolean non-String was falsy, so a guard yielding {@code 1} did not let its link through.
     */
    private boolean isTruthy(Object result) {
        return Truthiness.isTruthy(result);
    }

    /**
     * Says out loud which references a guard could not resolve.
     *
     * <p>An undefined variable is no longer an error — see {@code JEXLEvaluator} — which is what
     * stops a process stranding on a two-way branch, and it means a mistyped variable now sends the
     * process down the negative branch instead. That is silent from the process's side, so this is
     * the only thing standing between a typo and a wrong route nobody notices. It logs at warn
     * rather than failing: a guard that reads a variable set only on some paths is legitimate, and
     * common.
     */
    private void reportUndefined(io.mateu.workflow.application.services.JEXLEvaluator.Evaluation evaluation,
                                 String expression, String where, Process process) {
        if (evaluation.undefinedVariables().isEmpty()) {
            return;
        }
        log.warn("Guard '{}' on {} of process {} read {} that {} not set; treated as falsy. "
                        + "If that is a typo, this is where the process takes the wrong branch.",
                expression, where, process.getId(),
                evaluation.undefinedVariables().size() == 1
                        ? "the variable " + evaluation.undefinedVariables().iterator().next()
                        : "the variables " + evaluation.undefinedVariables(),
                evaluation.undefinedVariables().size() == 1 ? "was" : "were");
    }

    private TransitionResult handleEndStepTransition(Pass pass, List<StepExecution> stepExecutions, List<StepExecution> executableSteps, Map<String, Step> cache) {
        var process = pass.process;
        List<StepExecution> stepsToSave = new ArrayList<>();

        // Only the END-type executables complete. A co-eligible non-END sibling must NOT be
        // recorded COMPLETED — it never ran; it falls into the cancel stream below like every
        // other in-flight step.
        List<StepExecution> endSteps = executableSteps.stream()
                .filter(stepExecution -> StepType.END.equals(getStep(stepExecution, cache).type()))
                .toList();
        // Stamped finished, which withStatus alone does not do: an END step is completed straight
        // from here and never goes through updateStatus, so it was the one terminal execution in
        // the engine with no time on it at all — against what the field itself documents ("set when
        // the step reaches a terminal status"). It read as a step that never ran wherever the
        // record is shown by time, the process diagram's step numbers included.
        var now = EngineClock.now();
        endSteps.stream()
                .map(stepExecution -> stepExecution.withStatus(StepExecutionStatus.COMPLETED)
                        .withFinishedAt(now))
                .forEach(stepsToSave::add);

        // Cancel all remaining uncompleted steps
        stepExecutions.stream()
                .filter(execution -> !endSteps.contains(execution))
                .filter(execution -> List.of(StepExecutionStatus.PENDING,
                                StepExecutionStatus.CREATED,
                                StepExecutionStatus.RUNNING,
                                StepExecutionStatus.AWAITING_RETRY,
                                // A parked lock-waiter is uncompleted work like any other: when a
                                // sibling branch reaches END it is cancelled, and the process's
                                // terminal-state releaseAll drops it from the wait queue.
                                StepExecutionStatus.WAITING_ON_LOCK)
                        .contains(execution.getStatus()))
                .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                .forEach(stepsToSave::add);
        // A forEach cancelled here takes its iterations with it.
        stepsToSave.stream()
                .filter(execution -> StepExecutionStatus.CANCELLED.equals(execution.getStatus()) && execution.isContainer())
                .toList()
                .forEach(container -> StepExecutions.iterationsOf(container, pass.working).stream()
                        .filter(iteration -> iteration.getStatus() == StepExecutionStatus.CREATED
                                || iteration.getStatus().keepsTheProcessInFlight())
                        .map(iteration -> iteration.withStatus(StepExecutionStatus.CANCELLED))
                        .forEach(stepsToSave::add));

        Process updatedProcess = completeProcess(process);
        boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
        return new TransitionResult(updatedProcess, pass.saved(stepsToSave), processCompleted, false, pass.followUp);
    }

    private TransitionResult handleStandardOrImplicitCompletionTransition(Pass pass, List<StepExecution> stepExecutions, List<StepExecution> executableSteps, Map<String, Step> cache) {
        List<StepExecution> stepsToSave = new ArrayList<>();

        // Start eligible steps — a forEach one as the container of its iterations.
        for (var stepExecution : executableSteps) {
            var step = getStep(stepExecution, cache);
            if (step.forEach() != null) {
                startContainer(stepExecution, step, pass);
            } else {
                stepsToSave.add(stepExecution.start(pass.process));
            }
        }
        var process = pass.process;

        // Handle implicit completion if no executable steps are scheduled, and no active steps
        // remain — with one exception: a step held only by a link guard is waiting, not
        // unreachable. Its guard reads process variables, and the variables can still change; a
        // step whose links are all completed and one of whose guards is false is exactly the case
        // the guard was written to hold. Wrapping the process up around it would turn "this link
        // is not satisfied" into "this step is cancelled and the process is done", which is the
        // opposite of what a guard on a link means.
        var heldByAGuard = stepExecutions.stream()
                .anyMatch(execution -> isWaitingOnALinkGuard(execution, process, stepExecutions, cache));
        if (executableSteps.isEmpty() && !pass.followUp && hasNoActiveStepsRemaining(stepExecutions) && !heldByAGuard) {
            stepExecutions.stream()
                    .filter(execution -> StepExecutionStatus.CREATED.equals(execution.getStatus()))
                    .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                    .forEach(stepsToSave::add);

            if (canBeCompleted(process)) {
                Process updatedProcess = completeProcess(process);
                boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
                return new TransitionResult(updatedProcess, pass.saved(stepsToSave), processCompleted, false);
            }
        }

        return new TransitionResult(process, pass.saved(stepsToSave), false, false, pass.followUp);
    }

    /**
     * True when this step has not run, every step it waits for has completed, and the only thing
     * standing in its way is a guard on one of its links that is currently false.
     *
     * <p>That is a step being held, and it can be released: the guard reads process variables, so
     * a worker reporting a new value, a message arriving or an operator retrying a branch changes
     * the answer. A step that is instead waiting for something that never completed is a different
     * thing and is not covered here — that one really is unreachable once nothing is left running.
     */
    private boolean isWaitingOnALinkGuard(StepExecution stepExecution, Process process,
                                          List<StepExecution> stepExecutions, Map<String, Step> cache) {
        if (!StepExecutionStatus.CREATED.equals(stepExecution.getStatus())) {
            return false;
        }
        var step = getStep(stepExecution, cache);
        // A CHOICE branch is never "held waiting to be released": the split decides the moment the
        // CHOICE completes and does not wait for a straggler guard to flip. A branch it did not take
        // is discarded, not held, so it must not keep the process from completing.
        if (choiceLinkInto(step, stepExecutions, cache) != null) {
            return false;
        }
        var links = step.resolvedPreconditions();
        // Only a guard that means "wait" holds anything. A DISCARD guard — what a step-level
        // preconditionExpression folds into — says the flow did not come this way, so the step is
        // a branch not taken, and the process finishing around it is exactly right.
        if (links.isEmpty() || links.stream().noneMatch(Precondition::holdsWhenFalse)) {
            return false;
        }
        boolean everyLinkStepCompleted = links.stream().allMatch(link -> stepExecutions.stream()
                .filter(se -> link.stepId().equals(se.getStepId()))
                .anyMatch(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus())));
        return everyLinkStepCompleted
                && links.stream().filter(Precondition::holdsWhenFalse)
                        .anyMatch(link -> !evaluateGuard(link, step, process));
    }

    private boolean hasNoActiveStepsRemaining(List<StepExecution> stepExecutions) {
        // AWAITING_RETRY is active work: a step waiting out its backoff will run again, so a process
        // holding one has not reached the point where it can complete and cancel the rest.
        // WAITING_ON_LOCK likewise: a step parked in a lock queue will run once the lock frees, so
        // the process must not wrap up around it as if the branch were dead.
        return stepExecutions.stream()
                .noneMatch(execution -> List.of(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING,
                                StepExecutionStatus.AWAITING_RETRY, StepExecutionStatus.WAITING_ON_LOCK)
                        .contains(execution.getStatus()));
    }

    private boolean canBeCompleted(Process process) {
        return process.getStatus() != ProcessStatus.CANCELLED
                && process.getStatus() != ProcessStatus.ERROR
                && process.getStatus() != ProcessStatus.COMPLETED
                // Terminal saga states are never "completed" through the normal END path.
                && process.getStatus() != ProcessStatus.COMPENSATED
                && process.getStatus() != ProcessStatus.COMPENSATION_FAILED;
    }

    private Process completeProcess(Process process) {
        var completed = process.withCompletionPercentage(100).withStatus(ProcessStatus.COMPLETED);
        if (completed.getStarted() == null) {
            completed = completed.withStarted(EngineClock.now());
        }
        if (completed.getFinished() == null) {
            completed = completed.withFinished(EngineClock.now());
        }
        return completed;
    }
}
