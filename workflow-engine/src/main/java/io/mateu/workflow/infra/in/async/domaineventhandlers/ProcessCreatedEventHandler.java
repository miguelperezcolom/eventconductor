package io.mateu.workflow.infra.in.async.domaineventhandlers;

import io.mateu.workflow.application.usecases.process.start.StartProcessCommand;
import io.mateu.workflow.application.usecases.process.start.StartProcessUseCase;
import io.mateu.workflow.domain.events.ProcessCreated;
import io.mateu.workflow.domain.shared.DomainEvent;
import io.mateu.workflow.domain.shared.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessCreatedEventHandler implements DomainEventHandler<ProcessCreated> {

    final StartProcessUseCase startProcessUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ProcessCreated.class;
    }

    @Override
    public void handle(ProcessCreated e) {
        startProcessUseCase.handle(new StartProcessCommand(e.processId()));
    }
}
