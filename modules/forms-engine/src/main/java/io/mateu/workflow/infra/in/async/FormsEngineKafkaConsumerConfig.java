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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class FormsEngineKafkaConsumerConfig {

    final CreateTaskUseCase createTaskUseCase;
    final CancelTaskUseCase cancelTaskUseCase;
    final io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase escalateTaskUseCase;

    @Bean
    public Consumer<DomainEvent> consumeWorkerEventForFormsEngine() {
        return event -> {
            // Type and task only: the event may carry variable values (sensitive ones arrive sealed, but a
            // log line is no place for either).
            log.info("Received event: {}", event == null ? null : event.getClass().getSimpleName()
                    + (event instanceof TaskExecutionRequested task ? " " + task.taskExecutionId() : ""));
            if (event instanceof TaskExecutionRequested(
                    String taskExecutionId, String processId, String workflowDefinitionId, String stepId, String taskId,
                    java.util.List<io.mateu.workflow.dtos.Variable> variables, Integer attempt
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
            if (event instanceof io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested escalation) {

                new Thread(() -> escalateTaskUseCase.handle(escalation)).start();

            }
            if (event instanceof TaskCancellationRequested(String taskId)) {

                new Thread(() -> cancelTaskUseCase
                            .handle(new CancelTaskCommand(taskId))).start();

            }


        };
    }

}
