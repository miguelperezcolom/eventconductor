package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
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

    private void given(WorkflowStatus declared, WorkflowStatus runtime) {
        when(workflowDefinitionRepository.findById("wd-1")).thenReturn(Optional.of(
                new WorkflowDefinition("wd-1", "Order", 1, null, false, 0, false, null, 0,
                        List.of(), false, declared, runtime)));
    }

    private void create() {
        createProcessUseCase.handle(new CreateProcessCommand("p-1", "wd-1", "bk-1", List.of(), null));
    }

    @Test
    void refusesToCreateAProcessForAWorkflowAnOperatorDisabled() {
        given(WorkflowStatus.ACTIVE, WorkflowStatus.DISABLED);

        create();

        verify(processRepository, never()).save(any(Process.class));
        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void refusesToCreateAProcessForAWorkflowItsOwnDefinitionDisables() {
        given(WorkflowStatus.DISABLED, WorkflowStatus.ACTIVE);

        create();

        verify(processRepository, never()).save(any(Process.class));
    }

    @Test
    void refusesForAnArchivedWorkflowFromEitherSource() {
        given(WorkflowStatus.ACTIVE, WorkflowStatus.ARCHIVED);
        create();
        given(WorkflowStatus.ARCHIVED, WorkflowStatus.ACTIVE);
        create();

        verify(processRepository, never()).save(any(Process.class));
    }

    @Test
    void createsWhenNeitherSourceObjects() {
        given(WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE);

        create();

        verify(processRepository).save(any(Process.class));
    }
}
