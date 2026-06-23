package io.mateu.workflow.infra.in.async.processdomainevent;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessDomainEventUseCaseTest {

    @Mock DomainEventHandler<DomainEvent> handler;

    ProcessDomainEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessDomainEventUseCase(List.of(handler));
    }

    @Test
    void callsHandlerWhenItCanHandleEvent() {
        var event = new ProcessCreated("p-1", List.of());
        when(handler.canHandle(event)).thenReturn(true);

        useCase.handle(new ProcessDomainEventCommand(event));

        verify(handler).handle(event);
    }

    @Test
    void doesNotCallHandlerWhenItCannotHandleEvent() {
        var event = new ProcessCreated("p-1", List.of());
        when(handler.canHandle(event)).thenReturn(false);

        useCase.handle(new ProcessDomainEventCommand(event));

        verify(handler, never()).handle(any());
    }

    @Test
    void continuesProcessingWhenHandlerThrows() {
        var event = new ProcessCreated("p-1", List.of());
        when(handler.canHandle(event)).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(handler).handle(event);

        useCase.handle(new ProcessDomainEventCommand(event));

        verify(handler).handle(event);
    }

    @Test
    void callsMultipleHandlers() {
        DomainEventHandler<DomainEvent> handler2 = mock();
        var useCase2 = new ProcessDomainEventUseCase(List.of(handler, handler2));

        var event = new ProcessCreated("p-1", List.of());
        when(handler.canHandle(event)).thenReturn(true);
        when(handler2.canHandle(event)).thenReturn(true);

        useCase2.handle(new ProcessDomainEventCommand(event));

        verify(handler).handle(event);
        verify(handler2).handle(event);
    }
}
