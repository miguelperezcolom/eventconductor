package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.operate.SetProcessVariablesUseCase;
import io.mateu.workflow.application.usecases.process.operate.SetProcessVariablesCommand;
import io.mateu.workflow.application.usecases.process.operate.OperationVariables;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.SetProcessVariablesRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's change of a process's variables, on the pod that owns the process rather than on whichever one took the UI click, the REST call
 * or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class SetProcessVariablesRequestedEventHandler implements DomainEventHandler<SetProcessVariablesRequested> {

    final SetProcessVariablesUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return SetProcessVariablesRequested.class;
    }

    @Override
    public void handle(SetProcessVariablesRequested e) {
        useCase.handle(new SetProcessVariablesCommand(e.processId(), OperationVariables.toDomain(e.set()), e.remove(), e.reason(), e.requestedBy()));
    }
}
