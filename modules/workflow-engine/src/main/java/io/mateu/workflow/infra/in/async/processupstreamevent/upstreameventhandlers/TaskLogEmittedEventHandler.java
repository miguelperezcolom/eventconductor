package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.update.RegisterLogMessageCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.RegisterLogMessageUseCase;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskLogEmittedEventHandler implements DomainEventHandler<TaskLogEmitted> {

    final RegisterLogMessageUseCase registerLogMessageUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TaskLogEmitted.class;
    }

    @Override
    public void handle(TaskLogEmitted e) {
        registerLogMessageUseCase.handle(new RegisterLogMessageCommand(e.taskExecutionId(),
                e.messageType(),
                e.message()));
    }
}
