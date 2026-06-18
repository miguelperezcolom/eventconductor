package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
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

    public void handle(StepOverProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        List<StepExecution> executableSteps = new ArrayList<>();
        var stepExecutions = stepExecutionRepository.findByProcess(process);
        for (StepExecution stepExecution : stepExecutions) {
            if (StepExecutionStatus.COMPLETED.equals(stepExecution.getStatus())
                    || StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus())
                    || StepExecutionStatus.ERROR.equals(stepExecution.getStatus())) {
                continue;
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
                    } catch (Exception ignored) {
                        log.error("Error evaluating precondition expression '" + step.preconditionExpression() + "'", ignored);
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
            stepExecutions.stream().filter(execution -> List.of(StepExecutionStatus.PENDING,
                                    StepExecutionStatus.CREATED,
                                    StepExecutionStatus.RUNNING)
                    .contains(execution.getStatus()))
                    .map(execution -> execution.withStatus(StepExecutionStatus.CANCELLED))
                    .forEach(stepExecutionRepository::save);
            processRepository.save(process.withCompletionPercentage(100).withStatus(ProcessStatus.COMPLETED));
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
                    processRepository.save(process.withCompletionPercentage(100).withStatus(ProcessStatus.COMPLETED));
                }
            }
        }
    }

}
