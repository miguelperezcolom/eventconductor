package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

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
                || ProcessStatus.COMPENSATED.equals(process.getStatus())) {
            // A process being cancelled must not dispatch new steps. Same for a paused one:
            // in-flight steps may still complete (their reports are accepted), but their
            // successors are held here — and blocking-error handling is deferred — until
            // the process is resumed. COMPENSATED is terminal (saga rollback finished): return
            // it untouched so the blocking-error branch below can't flip it back to ERROR.
            return new TransitionResult(process, List.of(), false, false);
        }

        for (StepExecution stepExecution : stepExecutions) {
            if (isBlockingError(stepExecution)) {
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

        // Parse each step's JSON at most once per call: getStep is hit 2-3× per candidate
        // (eligibility, END detection, END transition) and pojoFromJson is not free.
        var stepCache = new HashMap<String, Step>();

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

        return handleStandardOrImplicitCompletionTransition(process, stepExecutions, executableSteps);
    }

    private boolean isBlockingError(StepExecution stepExecution) {
        return StepExecutionStatus.ERROR.equals(stepExecution.getStatus())
                || StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus());
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
        return checkPreconditionStep(step, stepExecutions) && evaluatePreconditionExpression(step, process);
    }

    private boolean checkPreconditionStep(Step step, List<StepExecution> stepExecutions) {
        // A precondition is satisfied once it has a COMPLETED step execution. An XOR join proceeds
        // as soon as ANY incoming branch has completed; every other step — including an AND join,
        // the default barrier — needs them ALL.
        java.util.function.Predicate<String> satisfied = preconditionStepId -> stepExecutions.stream()
                .filter(se -> preconditionStepId.equals(se.getStepId()))
                .anyMatch(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus()));
        boolean xorJoin = step.type() == StepType.JOIN && step.joinType() == JoinType.XOR;
        return xorJoin
                ? step.preconditions().stream().anyMatch(satisfied)
                : step.preconditions().stream().allMatch(satisfied);
    }

    private boolean evaluatePreconditionExpression(Step step, Process process) {
        if (step.preconditionExpression() == null || step.preconditionExpression().isEmpty()) {
            return true;
        }

        var variables = new HashMap<String, Object>();
        variables.put("process", process);
        variables.put("step", step);
        process.getVariables().forEach(variable -> variables.put(variable.name(), variable.value()));
        // Seeded AFTER the variables so the canonical value always wins: JEXL runs with
        // RESTRICTED permissions (no introspection of domain classes), so businessKey must
        // be available as a plain context variable — `process.businessKey` cannot evaluate.
        variables.put("businessKey", process.getBusinessKey());

        try {
            Object result = eval(step.preconditionExpression(), variables);
            return result != null && (result instanceof Boolean b && b 
                    || result instanceof String s && !s.isEmpty() && !"false".equals(s));
        } catch (Exception e) {
            // Fail closed: a guard that cannot be evaluated must not let the step run.
            log.error("Error evaluating precondition expression '{}' for step {}, step will not run",
                    step.preconditionExpression(), step.id(), e);
            return false;
        }
    }

    private TransitionResult handleEndStepTransition(Process process, List<StepExecution> stepExecutions, List<StepExecution> executableSteps, Map<String, Step> cache) {
        List<StepExecution> stepsToSave = new ArrayList<>();

        // Only the END-type executables complete. A co-eligible non-END sibling must NOT be
        // recorded COMPLETED — it never ran; it falls into the cancel stream below like every
        // other in-flight step.
        List<StepExecution> endSteps = executableSteps.stream()
                .filter(stepExecution -> StepType.END.equals(getStep(stepExecution, cache).type()))
                .toList();
        endSteps.stream()
                .map(stepExecution -> stepExecution.withStatus(StepExecutionStatus.COMPLETED))
                .forEach(stepsToSave::add);

        // Cancel all remaining uncompleted steps
        stepExecutions.stream()
                .filter(execution -> !endSteps.contains(execution))
                .filter(execution -> List.of(StepExecutionStatus.PENDING,
                                StepExecutionStatus.CREATED,
                                StepExecutionStatus.RUNNING)
                        .contains(execution.getStatus()))
                .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                .forEach(stepsToSave::add);

        Process updatedProcess = completeProcess(process);
        boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
        return new TransitionResult(updatedProcess, stepsToSave, processCompleted, false);
    }

    private TransitionResult handleStandardOrImplicitCompletionTransition(Process process, List<StepExecution> stepExecutions, List<StepExecution> executableSteps) {
        List<StepExecution> stepsToSave = new ArrayList<>();

        // Start eligible steps
        executableSteps.stream()
                .map(stepExecution -> stepExecution.start(process))
                .forEach(stepsToSave::add);

        // Handle implicit completion if no executable steps are scheduled, and no active steps remain
        if (executableSteps.isEmpty() && hasNoActiveStepsRemaining(stepExecutions)) {
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

    private boolean hasNoActiveStepsRemaining(List<StepExecution> stepExecutions) {
        return stepExecutions.stream()
                .noneMatch(execution -> List.of(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING)
                        .contains(execution.getStatus()));
    }

    private boolean canBeCompleted(Process process) {
        return process.getStatus() != ProcessStatus.CANCELLED 
                && process.getStatus() != ProcessStatus.ERROR 
                && process.getStatus() != ProcessStatus.COMPLETED;
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
