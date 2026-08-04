package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A disabled workflow accepts no new instances. The cron scheduler already honoured that; this
 * path did not, so everything that creates a process directly — the UI, an upstream event, an MCP
 * call — walked straight past a workflow that had been taken out of service.
 */
@ExtendWith(MockitoExtension.class)
class CreateProcessDisabledDefinitionTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock WorkflowDefinitionRepository workflowDefinitionRepository;
    @Mock WorkflowMetrics workflowMetrics;

    @InjectMocks CreateProcessUseCase createProcessUseCase;

    private void given(boolean disabled, boolean archived,
                       boolean declaredDisabled, boolean declaredArchived) {
        when(workflowDefinitionRepository.findById("wd-1")).thenReturn(Optional.of(
                new WorkflowDefinition("wd-1", "Order", 1, null, false, 0, false, null, 0,
                        List.of(), false, disabled, archived, declaredDisabled, declaredArchived)));
    }

    private void create() {
        createProcessUseCase.handle(new CreateProcessCommand("p-1", "wd-1", "bk-1", List.of(), null));
    }

    @Test
    void refusesToCreateAProcessForAWorkflowAnOperatorDisabled() {
        given(true, false, false, false);

        create();

        verify(processRepository, never()).save(any(Process.class));
        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void refusesToCreateAProcessForAWorkflowItsOwnDefinitionDisables() {
        given(false, false, true, false);

        create();

        verify(processRepository, never()).save(any(Process.class));
    }

    @Test
    void refusesForAnArchivedWorkflowFromEitherSource() {
        given(false, true, false, false);
        create();
        given(false, false, false, true);
        create();

        verify(processRepository, never()).save(any(Process.class));
    }

    @Test
    void createsWhenNeitherSourceObjects() {
        given(false, false, false, false);

        create();

        verify(processRepository).save(any(Process.class));
    }
}
