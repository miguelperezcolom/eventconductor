package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProcessCreationRequestedEventHandlerTest {

    @Mock CreateProcessUseCase createProcessUseCase;

    @InjectMocks ProcessCreationRequestedEventHandler handler;

    @Test
    void delegatesToCreateProcessUseCase() {
        handler.handle(new ProcessCreationRequested("wd-1", "BK-1", List.of(new Variable("k", "v"))));
        verify(createProcessUseCase).handle(any());
    }
}
