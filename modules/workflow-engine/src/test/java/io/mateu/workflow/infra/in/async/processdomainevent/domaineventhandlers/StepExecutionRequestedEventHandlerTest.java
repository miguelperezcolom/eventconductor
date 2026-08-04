package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionUseCase;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StepExecutionRequestedEventHandlerTest {

    @Mock StartStepExecutionUseCase startStepExecutionUseCase;

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks StepExecutionRequestedEventHandler handler;

    @Test
    void delegatesToStartStepExecutionUseCase() {
        handler.handle(new TaskExecutionRequested("se-1", "p-1", "wd-1", "s1", "task", List.of()));

        verify(startStepExecutionUseCase).handle(any());
    }
}
