package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.usecases.process.start.StartProcessUseCase;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProcessCreatedEventHandlerTest {

    @Mock StartProcessUseCase startProcessUseCase;
    @Mock StepOverProcessUseCase stepOverProcessUseCase;

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks ProcessCreatedEventHandler handler;

    @Test
    void callsStartAndStepOverOnProcessCreated() {
        handler.handle(new ProcessCreated("p-1", List.of()));

        verify(startProcessUseCase).handle(any());
        verify(stepOverProcessUseCase).handle(any());
    }
}
