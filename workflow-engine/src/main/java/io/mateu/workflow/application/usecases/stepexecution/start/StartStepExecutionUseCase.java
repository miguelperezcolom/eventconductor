package io.mateu.workflow.application.usecases.stepexecution.start;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.shared.AggregateRepository;
import io.mateu.workflow.infra.out.async.events.StepExecutionRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
public class StartStepExecutionUseCase {

    final AggregateRepository<StepExecution, String> stepExecutionRepository;
    private final StreamBridge streamBridge;

    public void handle(StartStepExecutionCommand command) {
        // crear y grabar proceso
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        streamBridge.send("worker-out-0", new StepExecutionRequested(
                stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getWorkflowDefinitionId(),
                stepExecution.getStepId(),
                stepExecution.getVariables()
        ));
        stepExecutionRepository.save(stepExecution.withStatus(StepExecutionStatus.PENDING));
        // enviar evento proceso creado (para step over)
    }

}
