package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.updates.ApplyProcessUpdateUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.ProcessUpdateRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Applies a process update on the pod that owns the process, rather than on whichever one took the REST
 * call, the MCP call — or, produced straight to the {@code upstream} topic, on nobody's behalf at all.
 */
@Service
@RequiredArgsConstructor
public class ProcessUpdateRequestedEventHandler implements DomainEventHandler<ProcessUpdateRequested> {

    final ApplyProcessUpdateUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ProcessUpdateRequested.class;
    }

    @Override
    public void handle(ProcessUpdateRequested e) {
        useCase.handle(e);
    }
}
