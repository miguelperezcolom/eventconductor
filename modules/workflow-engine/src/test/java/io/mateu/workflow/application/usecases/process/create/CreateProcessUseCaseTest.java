package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateProcessUseCaseTest {

    @Mock
    OutboxMessageEntityRepository outboxMessageEntityRepository;
    @Mock
    ProcessRepository processRepository;
    @Mock
    WorkflowDefinitionRepository workflowDefinitionRepository;
    @Mock
    StepExecutionRepository stepExecutionRepository;
    @Mock
    WorkflowMetrics workflowMetrics;

    @InjectMocks
    CreateProcessUseCase createProcessUseCase;

    @Test
    void shouldCreateProcess() {
        // given
        String workflowDefinitionId = "wd-1";
        Step step = new Step("step-1", workflowDefinitionId, StepType.ACTION, "Step 1", "Desc", null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0);
        WorkflowDefinition workflowDefinition = new WorkflowDefinition(
                workflowDefinitionId, "Test Workflow", 1, "Description", WorkflowDefinitionStatus.ACTIVE,
                null, false, 0, false, null, 0, List.of(step)
        );

        CreateProcessCommand command = new CreateProcessCommand(
                "process-1",
                workflowDefinitionId,
                "BK-1",
                List.of(new Variable("v1", "val1"))
        , null);

        when(workflowDefinitionRepository.findById(workflowDefinitionId)).thenReturn(Optional.of(workflowDefinition));

        // when
        createProcessUseCase.handle(command);

        // then
        verify(workflowDefinitionRepository).findById(workflowDefinitionId);
        verify(stepExecutionRepository, times(1)).save(any(StepExecution.class));
        verify(processRepository).save(any(io.mateu.workflow.domain.aggregates.Process.class));
        verify(workflowMetrics).processStarted(workflowDefinitionId);
    }

    @Test
    void shouldCreateProcessBornPausedWhenTheDefinitionIsPaused() {
        // given
        String workflowDefinitionId = "wd-1";
        Step step = new Step("step-1", workflowDefinitionId, StepType.ACTION, "Step 1", "Desc", null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0);
        WorkflowDefinition workflowDefinition = new WorkflowDefinition(
                workflowDefinitionId, "Test Workflow", 1, "Description", WorkflowDefinitionStatus.ACTIVE,
                null, false, 0, false, null, 0, List.of(step), true
        );

        CreateProcessCommand command = new CreateProcessCommand(
                "process-1",
                workflowDefinitionId,
                "BK-1",
                List.of(new Variable("v1", "val1"))
        , null);

        when(workflowDefinitionRepository.findById(workflowDefinitionId)).thenReturn(Optional.of(workflowDefinition));

        // when
        createProcessUseCase.handle(command);

        // then: creation is still accepted, but the process is born PAUSED with pausedAt set...
        var captor = org.mockito.ArgumentCaptor.forClass(io.mateu.workflow.domain.aggregates.Process.class);
        verify(processRepository).save(captor.capture());
        var saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getStatus()).isEqualTo(ProcessStatus.PAUSED);
        org.assertj.core.api.Assertions.assertThat(saved.getPausedAt()).isNotNull();
        // ...steps are created as usual...
        verify(stepExecutionRepository, times(1)).save(any(StepExecution.class));
        // ...and the ProcessCreated event survives the paused copy, so the (gated)
        // creation pipeline still runs.
        org.assertj.core.api.Assertions.assertThat(saved.popEvents())
                .anyMatch(event -> event instanceof io.mateu.workflow.dtos.events.domain.ProcessCreated);
    }
}
