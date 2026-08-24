package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.input.InputLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessUpstreamEventUseCaseTest {

    @Mock DomainEventHandler<DomainEvent> handler;

    ProcessUpstreamEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessUpstreamEventUseCase(List.of(handler));
    }

    @Test
    void callsHandlerWhenItCanHandle() {
        var event = new ProcessCreationRequested("wd-1", "BK", List.of());
        when(handler.canHandle(event)).thenReturn(true);

        useCase.handle(new ProcessUpstreamEventCommand(event));

        verify(handler).handle(event);
    }

    @Test
    void skipsHandlerWhenItCannotHandle() {
        var event = new ProcessCreationRequested("wd-1", "BK", List.of());
        when(handler.canHandle(event)).thenReturn(false);

        useCase.handle(new ProcessUpstreamEventCommand(event));

        verify(handler, never()).handle(any());
    }

    /**
     * The contract changed here, on purpose. This used to swallow a handler failure and carry on,
     * which is how an event the engine could not process disappeared: logged once, never retried,
     * never parked. What to do about a failure depends on how the event arrived — redeliver it if
     * the environment was at fault, park it on the dead-letter destination if the event itself is
     * defective — and only the caller knows that.
     */
    @Test
    void letsAHandlerFailureOutToWhoeverDeliveredTheEvent() {
        var event = new ProcessCreationRequested("wd-1", "BK", List.of());
        when(handler.canHandle(event)).thenReturn(true);
        doThrow(new RuntimeException("handler error")).when(handler).handle(event);

        assertThatThrownBy(() -> useCase.handle(new ProcessUpstreamEventCommand(event)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("handler error");
    }

    /**
     * The front door's size check, and where it sits: <em>before</em> the handlers, so an absurd
     * event costs nothing beyond the parse that delivered it. Thrown rather than logged for the same
     * reason a handler failure is — under Kafka the consumer parks it on the dead-letter destination,
     * because an event this size will be this size on every redelivery.
     */
    @Test
    void anEventCarryingSomethingAbsurdNeverReachesAHandler() {
        var event = new ProcessCreationRequested("wd-1", "BK",
                List.of(new Variable("payload", "x".repeat(InputLimits.MAX_VALUE_LENGTH + 1))));

        assertThatThrownBy(() -> useCase.handle(new ProcessUpstreamEventCommand(event)))
                .isInstanceOf(InputLimits.InputRejectedException.class)
                .hasMessageContaining("payload");

        verify(handler, never()).handle(any());
        verify(handler, never()).canHandle(any());
    }
}
