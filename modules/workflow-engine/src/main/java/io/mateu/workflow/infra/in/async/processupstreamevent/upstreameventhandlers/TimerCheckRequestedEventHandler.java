package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.checktimer.CheckTimerCommand;
import io.mateu.workflow.application.usecases.checktimer.CheckTimerUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TimerCheckRequestedEventHandler implements DomainEventHandler<TimerCheckRequested> {

    final CheckTimerUseCase checkTimerUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TimerCheckRequested.class;
    }

    @Override
    public void handle(TimerCheckRequested e) {
        checkTimerUseCase.handle(new CheckTimerCommand(e.processId()));
    }
}
