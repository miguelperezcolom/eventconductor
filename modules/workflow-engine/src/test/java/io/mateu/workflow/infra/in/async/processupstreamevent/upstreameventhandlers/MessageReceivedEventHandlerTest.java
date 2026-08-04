package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.correlatemessage.CorrelateMessageCommand;
import io.mateu.workflow.application.usecases.correlatemessage.CorrelateMessageUseCase;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageReceivedEventHandlerTest {

    @Mock CorrelateMessageUseCase correlateMessageUseCase;

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks MessageReceivedEventHandler handler;

    @Test
    void handlesMessageReceivedEvents() {
        assertThat(handler.eventClass()).isEqualTo(MessageReceived.class);
    }

    @Test
    void delegatesToCorrelateMessageUseCase() {
        handler.handle(new MessageReceived("payment-received", "bk-1",
                List.of(new Variable("paymentId", "P-9"))));

        verify(correlateMessageUseCase).handle(new CorrelateMessageCommand("payment-received", "bk-1",
                List.of(new io.mateu.workflow.domain.aggregates.Variable("paymentId", "P-9"))));
    }

    @Test
    void toleratesNullVariables() {
        handler.handle(new MessageReceived("payment-received", "bk-1", null));

        verify(correlateMessageUseCase).handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));
    }
}
