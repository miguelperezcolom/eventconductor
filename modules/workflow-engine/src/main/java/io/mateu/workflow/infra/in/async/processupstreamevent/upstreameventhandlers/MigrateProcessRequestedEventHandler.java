package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.operate.MigrateProcessCommand;
import io.mateu.workflow.application.usecases.process.operate.MigrateProcessUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.MigrateProcessRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs an operator's migration of a process onto another version of its definition, on the pod that owns
 * the process rather than on whichever one took the UI click, the REST call, the MCP call or the batch.
 */
@Service
@RequiredArgsConstructor
public class MigrateProcessRequestedEventHandler implements DomainEventHandler<MigrateProcessRequested> {

    final MigrateProcessUseCase useCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return MigrateProcessRequested.class;
    }

    @Override
    public void handle(MigrateProcessRequested e) {
        useCase.handle(new MigrateProcessCommand(e.processId(), e.targetVersion(), e.stepMapping(),
                e.variableMapping(), e.reason(), e.requestedBy()));
    }
}
