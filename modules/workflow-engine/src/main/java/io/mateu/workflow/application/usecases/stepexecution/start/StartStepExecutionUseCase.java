package io.mateu.workflow.application.usecases.stepexecution.start;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class StartStepExecutionUseCase {

    final StepExecutionRepository stepExecutionRepository;
    private final DownstreamEventPublisher downstreamEventPublisher;

    public void handle(StartStepExecutionCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();
        // Idempotency: only dispatch if the step is still waiting (PENDING).
        // A duplicate event arriving after the worker has already responded
        // (status RUNNING / COMPLETED / ERROR / …) is silently ignored.
        if (stepExecution.getStatus() != StepExecutionStatus.PENDING) {
            log.warn("Step execution {} is already in status {}, ignoring duplicate TaskExecutionRequested",
                    command.stepExecutionId(), stepExecution.getStatus());
            return;
        }
        var taskId = "";
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (StepType.USER_TASK.equals(step.type())) {
            taskId = "complete-form";
        }
        if (StepType.RULE.equals(step.type())) {
            taskId = "evaluate-rule";
        }
        downstreamEventPublisher.publish(new TaskExecutionRequested(
                stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getWorkflowDefinitionId(),
                stepExecution.getStepId(),
                taskId,
                stepExecution.getVariables().stream()
                        .map(variable -> new Variable(variable.name(), variable.value()))
                        .toList()
        ));
    }

}
