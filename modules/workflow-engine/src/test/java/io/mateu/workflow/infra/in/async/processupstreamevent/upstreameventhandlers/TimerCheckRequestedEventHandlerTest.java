package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.checktimer.CheckTimerCommand;
import io.mateu.workflow.application.usecases.checktimer.CheckTimerUseCase;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TimerCheckRequestedEventHandlerTest {

    @Mock CheckTimerUseCase checkTimerUseCase;

    @InjectMocks TimerCheckRequestedEventHandler handler;

    @Test
    void handlesTimerCheckRequestedEvents() {
        assertThat(handler.eventClass()).isEqualTo(TimerCheckRequested.class);
    }

    @Test
    void delegatesToCheckTimerUseCase() {
        handler.handle(new TimerCheckRequested("p-1"));

        verify(checkTimerUseCase).handle(new CheckTimerCommand("p-1"));
    }
}
