package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskStatusChangedEventHandlerTest {

    @Mock UpdateStepExecutionUseCase updateStepExecutionUseCase;

    @InjectMocks TaskStatusChangedEventHandler handler;

    @Test
    void delegatesToUpdateStepExecutionUseCase() {
        var event = new TaskStatusChanged("se-1", TaskStatus.COMPLETED, List.of(new Variable("k", "v")));
        handler.handle(event);
        verify(updateStepExecutionUseCase).handle(any());
    }
}
