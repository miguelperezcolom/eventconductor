package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.update.RegisterLogMessageUseCase;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskLogEmittedEventHandlerTest {

    @Mock RegisterLogMessageUseCase registerLogMessageUseCase;

    @InjectMocks TaskLogEmittedEventHandler handler;

    @Test
    void delegatesToRegisterLogMessageUseCase() {
        handler.handle(new TaskLogEmitted("se-1", MessageType.Info, "Processing step"));
        verify(registerLogMessageUseCase).handle(any());
    }
}
