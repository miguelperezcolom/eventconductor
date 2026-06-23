package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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

    @Test
    void continuesWhenHandlerThrows() {
        var event = new ProcessCreationRequested("wd-1", "BK", List.of());
        when(handler.canHandle(event)).thenReturn(true);
        doThrow(new RuntimeException("handler error")).when(handler).handle(event);

        useCase.handle(new ProcessUpstreamEventCommand(event));

        verify(handler).handle(event);
    }
}
