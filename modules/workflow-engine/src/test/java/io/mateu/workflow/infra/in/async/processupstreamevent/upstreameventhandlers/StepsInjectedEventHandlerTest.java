package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.inject.InjectStepsUseCase;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StepsInjectedEventHandlerTest {

    @Mock InjectStepsUseCase injectStepsUseCase;

    @Mock io.mateu.workflow.infra.in.async.processupstreamevent.UnkeyedEventRouter unkeyedEventRouter;

    @InjectMocks StepsInjectedEventHandler handler;

    @Test
    void delegatesToInjectStepsUseCase() {
        var event = new StepsInjected("se-1", "p-1", "[]");
        when(unkeyedEventRouter.rerouted(event)).thenReturn(false);
        handler.handle(event);
        verify(injectStepsUseCase).handle(any());
    }

    @Test
    void doesNotHandleWhenRerouted() {
        var event = new StepsInjected("se-1", "p-1", "[]");
        when(unkeyedEventRouter.rerouted(event)).thenReturn(true);
        handler.handle(event);
        verify(injectStepsUseCase, org.mockito.Mockito.never()).handle(any());
    }
}
