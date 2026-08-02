package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's resume on the pod that owns the process, rather than on whichever one
 * happened to take the UI click or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class ResumeProcessRequestedEventHandler implements DomainEventHandler<ResumeProcessRequested> {

    final ResumeProcessUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ResumeProcessRequested.class;
    }

    @Override
    public void handle(ResumeProcessRequested e) {
        useCase.handle(new ResumeProcessCommand(e.processId()));
    }
}
