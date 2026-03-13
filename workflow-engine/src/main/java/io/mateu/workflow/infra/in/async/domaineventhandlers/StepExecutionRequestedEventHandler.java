package io.mateu.workflow.infra.in.async.domaineventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionUseCase;
import io.mateu.workflow.domain.events.StepExecutionRequested;
import io.mateu.workflow.domain.shared.DomainEvent;
import io.mateu.workflow.domain.shared.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepExecutionRequestedEventHandler implements DomainEventHandler<StepExecutionRequested> {

    final StartStepExecutionUseCase startStepExecutionUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return StepExecutionRequested.class;
    }

    @Override
    public void handle(StepExecutionRequested e) {
        startStepExecutionUseCase.handle(new StartStepExecutionCommand(e.stepExecution().id()));
    }
}
