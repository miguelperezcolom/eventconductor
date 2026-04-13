package io.mateu.workflow.infra.in.async.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProcessCreationRequestedEventHandler implements DomainEventHandler<ProcessCreationRequested> {

    final CreateProcessUseCase createProcessUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ProcessCreationRequested.class;
    }

    @Override
    public void handle(ProcessCreationRequested e) {
        createProcessUseCase.handle(new CreateProcessCommand(
                UUID.randomUUID().toString(),
                e.workflowDefinitionId(),
                e.businessKey(),
                e.variables().stream()
                        .map(variable -> new Variable(variable.name(), variable.value()))
                        .toList()
        ));
    }
}
