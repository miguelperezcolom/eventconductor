package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

@Service
@RequiredArgsConstructor
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
                if (step.precondition() != null
                        && step.precondition().expression() != null
                        && !step.precondition().expression().isEmpty()) {
                    run = (boolean) eval(step.precondition().expression(), Map.of("process", process, "step", step));
                }
                if (run) {
                    executableSteps.add(stepExecution);
                    if (!step.parallel()) {
                        break;
                    }
                }
            }
        }
        executableSteps.stream().map(stepExecution -> stepExecution.start(process.getVariables()))
                .forEach(stepExecutionRepository::save);
    }

}
