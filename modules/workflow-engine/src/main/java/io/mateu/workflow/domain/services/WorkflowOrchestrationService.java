package io.mateu.workflow.domain.services;

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
    public static class TransitionResult {
        Process             updatedProcess;
        List<StepExecution> stepsToSave;
        boolean             processCompleted;
        boolean             processErrored;
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

        for (StepExecution stepExecution : stepExecutions) {
            if (isBlockingError(stepExecution, stepCache)) {
                // A step that failed blocks the flow: don't schedule successors or complete the process.
                Process updatedProcess = process;
                boolean processErrored = false;
                if (process.getStatus() != ProcessStatus.ERROR) {
                    updatedProcess = process.withStatus(ProcessStatus.ERROR);
                    processErrored = true;
                }
                return new TransitionResult(updatedProcess, List.of(), false, processErrored);
            }
        }

        // Pure dataflow: every CREATED step whose preconditions are ALL satisfied (and whose
        // guard expression is truthy) starts now, concurrently. There is no ordering between
        // steps beyond the precondition graph — active steps never block unrelated branches.
        List<StepExecution> executableSteps = stepExecutions.stream()
                .filter(this::isCreated)
                .filter(stepExecution -> shouldRunStep(stepExecution, process, stepExecutions, stepCache))
                .toList();

        boolean hasEndStep = executableSteps.stream()
                .anyMatch(stepExecution -> StepType.END.equals(getStep(stepExecution, stepCache).type()));

        if (hasEndStep) {
            return handleEndStepTransition(process, stepExecutions, executableSteps, stepCache);
        }

        return handleStandardOrImplicitCompletionTransition(process, stepExecutions, executableSteps, stepCache);
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
        return xorJoin
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
        if (result == null) {
            return false;
        }
        if (result instanceof Boolean b) {
            return b;
        }
        if (result instanceof String s) {
            return !s.isEmpty() && !"false".equals(s);
        }
        if (result instanceof Number n) {
            return n.doubleValue() != 0d && !Double.isNaN(n.doubleValue());
        }
        return true;
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

    private TransitionResult handleEndStepTransition(Process process, List<StepExecution> stepExecutions, List<StepExecution> executableSteps, Map<String, Step> cache) {
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
        var now = java.time.LocalDateTime.now();
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
                                StepExecutionStatus.AWAITING_RETRY)
                        .contains(execution.getStatus()))
                .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                .forEach(stepsToSave::add);

        Process updatedProcess = completeProcess(process);
        boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
        return new TransitionResult(updatedProcess, stepsToSave, processCompleted, false);
    }

    private TransitionResult handleStandardOrImplicitCompletionTransition(Process process, List<StepExecution> stepExecutions, List<StepExecution> executableSteps, Map<String, Step> cache) {
        List<StepExecution> stepsToSave = new ArrayList<>();

        // Start eligible steps
        executableSteps.stream()
                .map(stepExecution -> stepExecution.start(process))
                .forEach(stepsToSave::add);

        // Handle implicit completion if no executable steps are scheduled, and no active steps
        // remain — with one exception: a step held only by a link guard is waiting, not
        // unreachable. Its guard reads process variables, and the variables can still change; a
        // step whose links are all completed and one of whose guards is false is exactly the case
        // the guard was written to hold. Wrapping the process up around it would turn "this link
        // is not satisfied" into "this step is cancelled and the process is done", which is the
        // opposite of what a guard on a link means.
        var heldByAGuard = stepExecutions.stream()
                .anyMatch(execution -> isWaitingOnALinkGuard(execution, process, stepExecutions, cache));
        if (executableSteps.isEmpty() && hasNoActiveStepsRemaining(stepExecutions) && !heldByAGuard) {
            stepExecutions.stream()
                    .filter(execution -> StepExecutionStatus.CREATED.equals(execution.getStatus()))
                    .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                    .forEach(stepsToSave::add);

            if (canBeCompleted(process)) {
                Process updatedProcess = completeProcess(process);
                boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
                return new TransitionResult(updatedProcess, stepsToSave, processCompleted, false);
            }
        }

        return new TransitionResult(process, stepsToSave, false, false);
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
        return stepExecutions.stream()
                .noneMatch(execution -> List.of(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING,
                                StepExecutionStatus.AWAITING_RETRY)
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
            completed = completed.withStarted(LocalDateTime.now());
        }
        if (completed.getFinished() == null) {
            completed = completed.withFinished(LocalDateTime.now());
        }
        return completed;
    }
}
