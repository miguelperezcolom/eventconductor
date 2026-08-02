package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's retry of a single step on the pod that owns the process, rather than on
 * whichever one happened to take the UI click.
 */
@Service
@RequiredArgsConstructor
public class RetryStepExecutionRequestedEventHandler implements DomainEventHandler<RetryStepExecutionRequested> {

    final RetryStepExecutionUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return RetryStepExecutionRequested.class;
    }

    @Override
    public void handle(RetryStepExecutionRequested e) {
        useCase.handle(new RetryStepExecutionCommand(e.stepExecutionId()));
    }
}
