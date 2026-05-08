package io.mateu.workflow.infra.in.async.upstreameventhandlers;

import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksCommand;
import io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks.TriggerTimeoutChecksUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TimeoutCheckRequestedEventHandler implements DomainEventHandler<TimeoutCheckRequested> {

    final TriggerTimeoutChecksUseCase triggerTimeoutChecksUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TimeoutCheckRequested.class;
    }

    @Override
    public void handle(TimeoutCheckRequested e) {
        triggerTimeoutChecksUseCase.handle(new TriggerTimeoutChecksCommand(e.processId()));
    }
}
