package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's pause on the pod that owns the process, rather than on whichever one
 * happened to take the UI click or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class PauseProcessRequestedEventHandler implements DomainEventHandler<PauseProcessRequested> {

    final PauseProcessUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return PauseProcessRequested.class;
    }

    @Override
    public void handle(PauseProcessRequested e) {
        useCase.handle(new PauseProcessCommand(e.processId()));
    }
}
