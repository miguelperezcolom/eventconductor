package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessLocks;
import io.mateu.workflow.domain.aggregates.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

@Service
@RequiredArgsConstructor
@Slf4j
public class StepOverProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessLockService processLockService;
    final WorkflowMetrics workflowMetrics;

    public void handle(StepOverProcessCommand command) {
        // Serialize per process: two concurrent step-overs (e.g. two parallel steps
        // completing at once, or two pods handling events for the same process) would
        // both see the next step as CREATED and dispatch it twice.
        if (!ProcessLocks.lockWithRetry(processLockService, command.processId())) {
            log.error("Could not acquire lock for process {}, skipping step-over (another node is working on it)",
                    command.processId());
            return;
        }
        try {
            doHandle(command);
        } finally {
            processLockService.unlock(command.processId());
        }
    }

    private void doHandle(StepOverProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (ProcessStatus.CANCELLED.equals(process.getStatus())) {
            // A process being cancelled must not dispatch new steps.
            return;
        }
        List<StepExecution> executableSteps = new ArrayList<>();
        var stepExecutions = stepExecutionRepository.findByProcess(process);
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
                    var errored = process.withStatus(ProcessStatus.ERROR);
                    processRepository.save(errored);
                    workflowMetrics.processErrored(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(errored));
                }
                return;
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
        var endStep = executableSteps.stream().filter(stepExecution -> StepType.END.equals(pojoFromJson(stepExecution.getStepJson(), Step.class).type())).findAny();
        if (endStep.isPresent()) {
            executableSteps.stream().map(stepExecution -> stepExecution.withStatus(StepExecutionStatus.COMPLETED))
                    .forEach(stepExecutionRepository::save);
            stepExecutions.stream()
                    .filter(execution -> !executableSteps.contains(execution))
                    .filter(execution -> List.of(StepExecutionStatus.PENDING,
                                    StepExecutionStatus.CREATED,
                                    StepExecutionStatus.RUNNING)
                    .contains(execution.getStatus()))
                    .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                    .forEach(stepExecutionRepository::save);
            var completed = complete(process);
            processRepository.save(completed);
            if (process.getStatus() != ProcessStatus.COMPLETED) {
                workflowMetrics.processCompleted(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(completed));
            }
            return;
        }
        executableSteps.stream().map(stepExecution -> stepExecution.start(process.getVariables()))
                .forEach(stepExecutionRepository::save);
        if (executableSteps.isEmpty()) {
            var remaining = stepExecutions.stream().filter(execution -> List.of(StepExecutionStatus.PENDING,
                            StepExecutionStatus.RUNNING)
                    .contains(execution.getStatus())).findAny();
            if (remaining.isEmpty()) {
                stepExecutions.stream().filter(execution -> StepExecutionStatus.CREATED.equals(execution.getStatus()))
                        .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                        .forEach(stepExecutionRepository::save);
                if (process.getStatus() != ProcessStatus.CANCELLED && process.getStatus() != ProcessStatus.ERROR && process.getStatus() != ProcessStatus.COMPLETED) {
                    var completed = complete(process);
                    processRepository.save(completed);
                    workflowMetrics.processCompleted(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(completed));
                }
            }
        }
    }

    private io.mateu.workflow.domain.aggregates.Process complete(io.mateu.workflow.domain.aggregates.Process process) {
        var completed = process.withCompletionPercentage(100).withStatus(ProcessStatus.COMPLETED);
        if (completed.getStarted() == null) {
            completed = completed.withStarted(java.time.LocalDateTime.now());
        }
        if (completed.getFinished() == null) {
            completed = completed.withFinished(java.time.LocalDateTime.now());
        }
        return completed;
    }

}
