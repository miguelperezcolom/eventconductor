package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's cancellation on the pod that owns the process, rather than on whichever one
 * happened to take the UI click or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class CancelProcessRequestedEventHandler implements DomainEventHandler<ProcessCancellationRequested> {

    final CancelProcessUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ProcessCancellationRequested.class;
    }

    @Override
    public void handle(ProcessCancellationRequested e) {
        useCase.handle(new CancelProcessCommand(e.processId()));
    }
}
