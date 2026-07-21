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
        if (ProcessStatus.CANCELLED.equals(process.getStatus())) {
            // A process being cancelled must not dispatch new steps.
            return new TransitionResult(process, List.of(), false, false);
        }

        List<StepExecution> executableSteps = new ArrayList<>();
        List<StepExecution> stepsToSave = new ArrayList<>();
        boolean processErrored = false;
        Process updatedProcess = process;

        for (StepExecution stepExecution : stepExecutions) {
            if (StepExecutionStatus.COMPLETED.equals(stepExecution.getStatus())
                    || StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus())) {
                continue;
            }
            if (StepExecutionStatus.ERROR.equals(stepExecution.getStatus())
                    || StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus())) {
                // A step that failed for good (retries exhausted) blocks the flow: don't
                // schedule successors and don't complete the process. Remaining steps stay
                // CREATED so a manual retry can resume the flow.
                if (process.getStatus() != ProcessStatus.ERROR) {
                    updatedProcess = process.withStatus(ProcessStatus.ERROR);
                    processErrored = true;
                }
                return new TransitionResult(updatedProcess, List.of(), false, processErrored);
            }
            if (StepExecutionStatus.RUNNING.equals(stepExecution.getStatus())
                    || StepExecutionStatus.PENDING.equals(stepExecution.getStatus())) {
                break;
            }
            if (StepExecutionStatus.CREATED.equals(stepExecution.getStatus())) {
                var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
                boolean run = true;
                if (step.preconditionStepId() != null && !step.preconditionStepId().isEmpty()) {
                    run = stepExecutions.stream()
                            .filter(se -> step.preconditionStepId().equals(se.getStepId()))
                            .anyMatch(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus()));
                }
                if (step.preconditionExpression() != null
                        && !step.preconditionExpression().isEmpty()) {
                    var variables = new HashMap<String, Object>();
                    variables.put("process", process);
                    variables.put("step", step);
                    process.getVariables().forEach(variable -> variables.put(variable.name(), variable.value()));
                    try {
                        Object result = eval(step.preconditionExpression(), variables);
                        run &= result != null && (result instanceof Boolean b && b || result instanceof String s && !s.isEmpty() && !"false".equals(s));
                    } catch (Exception e) {
                        // Fail closed: a guard that cannot be evaluated must not let the step run.
                        run = false;
                        log.error("Error evaluating precondition expression '" + step.preconditionExpression()
                                + "' for step " + step.id() + ", step will not run", e);
                    }
                }
                if (run) {
                    executableSteps.add(stepExecution);
                    if (!step.parallel()) {
                        break;
                    }
                }
            }
        }

        var endStep = executableSteps.stream()
                .filter(stepExecution -> StepType.END.equals(pojoFromJson(stepExecution.getStepJson(), Step.class).type()))
                .findAny();

        if (endStep.isPresent()) {
            executableSteps.stream()
                    .map(stepExecution -> stepExecution.withStatus(StepExecutionStatus.COMPLETED))
                    .forEach(stepsToSave::add);

            stepExecutions.stream()
                    .filter(execution -> !executableSteps.contains(execution))
                    .filter(execution -> List.of(StepExecutionStatus.PENDING,
                                    StepExecutionStatus.CREATED,
                                    StepExecutionStatus.RUNNING)
                            .contains(execution.getStatus()))
                    .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                    .forEach(stepsToSave::add);

            updatedProcess = complete(process);
            boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
            return new TransitionResult(updatedProcess, stepsToSave, processCompleted, false);
        }

        executableSteps.stream()
                .map(stepExecution -> stepExecution.start(process.getVariables()))
                .forEach(stepsToSave::add);

        if (executableSteps.isEmpty()) {
            var remaining = stepExecutions.stream()
                    .filter(execution -> List.of(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING)
                            .contains(execution.getStatus()))
                    .findAny();

            if (remaining.isEmpty()) {
                stepExecutions.stream()
                        .filter(execution -> StepExecutionStatus.CREATED.equals(execution.getStatus()))
                        .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                        .forEach(stepsToSave::add);

                if (process.getStatus() != ProcessStatus.CANCELLED 
                        && process.getStatus() != ProcessStatus.ERROR 
                        && process.getStatus() != ProcessStatus.COMPLETED) {
                    updatedProcess = complete(process);
                    boolean processCompleted = process.getStatus() != ProcessStatus.COMPLETED;
                    return new TransitionResult(updatedProcess, stepsToSave, processCompleted, false);
                }
            }
        }

        return new TransitionResult(updatedProcess, stepsToSave, false, false);
    }

    private Process complete(Process process) {
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
