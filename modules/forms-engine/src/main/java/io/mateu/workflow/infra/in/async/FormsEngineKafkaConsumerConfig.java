package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.usecases.canceltask.CancelTaskCommand;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class FormsEngineKafkaConsumerConfig {

    final CreateTaskUseCase createTaskUseCase;
    final CancelTaskUseCase cancelTaskUseCase;

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent() {
        return event -> {
            log.info("Received event: " + event);
            if (event instanceof TaskExecutionRequested(
                    String taskExecutionId, String processId, String workflowDefinitionId, String stepId, String taskId,
                    java.util.List<io.mateu.workflow.dtos.Variable> variables
            )) {

                if ("complete-form".equals(taskId)) {
                    var formId = variables.stream()
                            .filter(variable -> "formId".equals(variable.name()))
                            .findAny().orElseThrow().value();
                    new Thread(() -> createTaskUseCase
                            .handle(new CreateTaskCommand(
                                    taskExecutionId,
                                    processId,
                                    workflowDefinitionId,
                                    stepId,
                                    formId,
                                    variables.stream()
                                            .map(variable -> new Variable(variable.name(), variable.value()))
                                            .toList()))).start();
                }

            }
            if (event instanceof TaskCancellationRequested(String taskId)) {

                new Thread(() -> cancelTaskUseCase
                            .handle(new CancelTaskCommand(taskId))).start();

            }


        };
    }

}
