package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.correlatemessage.CorrelateMessageCommand;
import io.mateu.workflow.application.usecases.correlatemessage.CorrelateMessageUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageReceivedEventHandler implements DomainEventHandler<MessageReceived> {

    final CorrelateMessageUseCase correlateMessageUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return MessageReceived.class;
    }

    @Override
    public void handle(MessageReceived e) {
        correlateMessageUseCase.handle(new CorrelateMessageCommand(
                e.messageName(),
                e.correlationKey(),
                e.variables() == null ? List.of() : e.variables().stream()
                        .map(variable -> new Variable(variable.name(), variable.value())).toList()));
    }
}
