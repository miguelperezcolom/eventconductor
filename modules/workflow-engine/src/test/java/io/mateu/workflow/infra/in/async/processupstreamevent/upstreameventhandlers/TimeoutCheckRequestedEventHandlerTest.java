package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.checktimeout.CheckTimeoutUseCase;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TimeoutCheckRequestedEventHandlerTest {

    @Mock CheckTimeoutUseCase checkTimeoutUseCase;

    @InjectMocks TimeoutCheckRequestedEventHandler handler;

    @Test
    void delegatesToCheckTimeoutUseCase() {
        handler.handle(new TimeoutCheckRequested("p-1"));
        verify(checkTimeoutUseCase).handle(any());
    }
}
