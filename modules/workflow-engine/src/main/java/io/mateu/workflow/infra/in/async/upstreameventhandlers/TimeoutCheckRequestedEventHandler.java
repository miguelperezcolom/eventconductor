package io.mateu.workflow.infra.in.async.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksCommand;
import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TimeoutCheckRequestedEventHandler implements DomainEventHandler<TimeoutCheckRequested> {

    final TriggerTimeoutChecksUseCase createProcessUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TimeoutCheckRequested.class;
    }

    @Override
    public void handle(TimeoutCheckRequested e) {
        createProcessUseCase.handle(new TriggerTimeoutChecksCommand());
    }
}
