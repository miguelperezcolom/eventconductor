package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.restart.RestartProcessCommand;
import io.mateu.workflow.application.usecases.process.restart.RestartProcessUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's restart of a whole process on the pod that owns the process, rather than on
 * whichever one happened to take the UI click or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class RestartProcessRequestedEventHandler implements DomainEventHandler<RestartProcessRequested> {

    final RestartProcessUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return RestartProcessRequested.class;
    }

    @Override
    public void handle(RestartProcessRequested e) {
        useCase.handle(new RestartProcessCommand(e.processId()));
    }
}
