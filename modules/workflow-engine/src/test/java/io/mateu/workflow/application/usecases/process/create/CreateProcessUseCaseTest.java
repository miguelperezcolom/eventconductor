package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
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

    @InjectMocks
    CreateProcessUseCase createProcessUseCase;

    @Test
    void shouldCreateProcess() {
        // given
        String workflowDefinitionId = "wd-1";
        Step step = new Step("step-1", workflowDefinitionId, StepType.ACTION, "Step 1", "Desc", null, false, "topic", null, false, 0, 0, null);
        WorkflowDefinition workflowDefinition = new WorkflowDefinition(
                workflowDefinitionId, "Test Workflow", 1, "Description", WorkflowDefinitionStatus.ACTIVE,
                false, 0, false, List.of(step)
        );

        CreateProcessCommand command = new CreateProcessCommand(
                "process-1",
                workflowDefinitionId,
                "BK-1",
                List.of(new Variable("v1", "val1"))
        );

        when(workflowDefinitionRepository.findById(workflowDefinitionId)).thenReturn(Optional.of(workflowDefinition));

        // when
        createProcessUseCase.handle(command);

        // then
        verify(workflowDefinitionRepository).findById(workflowDefinitionId);
        verify(stepExecutionRepository, times(1)).save(any(StepExecution.class));
        verify(processRepository).save(any(io.mateu.workflow.domain.aggregates.Process.class));
    }
}
