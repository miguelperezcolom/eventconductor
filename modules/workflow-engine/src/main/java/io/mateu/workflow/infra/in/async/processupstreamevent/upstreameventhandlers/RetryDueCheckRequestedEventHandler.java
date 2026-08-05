package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.checkretry.CheckRetryCommand;
import io.mateu.workflow.application.usecases.checkretry.CheckRetryUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.RetryDueCheckRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryDueCheckRequestedEventHandler implements DomainEventHandler<RetryDueCheckRequested> {

    final CheckRetryUseCase checkRetryUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return RetryDueCheckRequested.class;
    }

    @Override
    public void handle(RetryDueCheckRequested e) {
        checkRetryUseCase.handle(new CheckRetryCommand(e.processId()));
    }
}
