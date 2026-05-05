package io.mateu.workflow.application.usecases.stepexecution.start;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class StartStepExecutionUseCase {

    final StepExecutionRepository stepExecutionRepository;
    private final StreamBridge streamBridge;

    public void handle(StartStepExecutionCommand command) {
        // crear y grabar proceso
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();
        var taskId = "";
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (StepType.USER_TASK.equals(step.type())) {
            taskId = "complete-form";
        }
        streamBridge.send("downstream", new TaskExecutionRequested(
                stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getWorkflowDefinitionId(),
                stepExecution.getStepId(),
                taskId,
                stepExecution.getVariables().stream()
                        .map(variable -> new Variable(variable.name(), variable.value()))
                        .toList()
        ));
        // enviar evento proceso creado (para step over)
    }

}
