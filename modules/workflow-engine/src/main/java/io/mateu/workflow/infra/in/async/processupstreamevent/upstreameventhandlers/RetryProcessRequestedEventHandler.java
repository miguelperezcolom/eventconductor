package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's retry of a whole process on the pod that owns the process, rather than on whichever one
 * happened to take the UI click or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class RetryProcessRequestedEventHandler implements DomainEventHandler<RetryProcessRequested> {

    final RetryProcessUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return RetryProcessRequested.class;
    }

    @Override
    public void handle(RetryProcessRequested e) {
        useCase.handle(new RetryProcessCommand(e.processId()));
    }
}
