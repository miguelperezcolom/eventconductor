package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.operate.CompleteStepManuallyUseCase;
import io.mateu.workflow.application.usecases.process.operate.CompleteStepManuallyCommand;
import io.mateu.workflow.application.usecases.process.operate.OperationVariables;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.CompleteStepManuallyRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's manual completion of a step, on the pod that owns the process rather than on whichever one took the UI click, the REST call
 * or the MCP call.
 */
@Service
@RequiredArgsConstructor
public class CompleteStepManuallyRequestedEventHandler implements DomainEventHandler<CompleteStepManuallyRequested> {

    final CompleteStepManuallyUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return CompleteStepManuallyRequested.class;
    }

    @Override
    public void handle(CompleteStepManuallyRequested e) {
        useCase.handle(new CompleteStepManuallyCommand(e.processId(), e.stepExecutionId(), OperationVariables.toDomain(e.outputVariables()), e.reason(), e.requestedBy()));
    }
}
