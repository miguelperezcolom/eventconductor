package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.checktimeout.CheckTimeoutCommand;
import io.mateu.workflow.application.usecases.checktimeout.CheckTimeoutUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TimeoutCheckRequestedEventHandler implements DomainEventHandler<TimeoutCheckRequested> {

    final CheckTimeoutUseCase checkTimeoutUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TimeoutCheckRequested.class;
    }

    @Override
    public void handle(TimeoutCheckRequested e) {
        checkTimeoutUseCase.handle(new CheckTimeoutCommand(e.processId()));
    }
}
