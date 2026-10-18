package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.operate.RetryFromStepUseCase;
import io.mateu.workflow.application.usecases.process.operate.RetryFromStepCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.RetryFromStepRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's retry of a process from one of its steps, on the pod that owns the process rather than on whichever one took the UI click, the REST call
 * or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class RetryFromStepRequestedEventHandler implements DomainEventHandler<RetryFromStepRequested> {

    final RetryFromStepUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return RetryFromStepRequested.class;
    }

    @Override
    public void handle(RetryFromStepRequested e) {
        useCase.handle(new RetryFromStepCommand(e.processId(), e.stepId(), e.reason(), e.requestedBy()));
    }
}
