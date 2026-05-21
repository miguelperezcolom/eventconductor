package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepExecutionRequestedEventHandler implements DomainEventHandler<TaskExecutionRequested> {

    final StartStepExecutionUseCase startStepExecutionUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TaskExecutionRequested.class;
    }

    @Override
    public void handle(TaskExecutionRequested e) {
        startStepExecutionUseCase.handle(new StartStepExecutionCommand(e.taskExecutionId()));
    }
}
