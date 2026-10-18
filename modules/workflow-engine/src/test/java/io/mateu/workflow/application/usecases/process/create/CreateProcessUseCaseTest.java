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
        Step step = new Step("step-1", workflowDefinitionId, StepType.ACTION, "Step 1", "Desc", null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        WorkflowDefinition workflowDefinition = new WorkflowDefinition(
                workflowDefinitionId, "Test Workflow", 1, "Description",  false, 0, false, null, 0, List.of(step)
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
    void aCreationForAWorkflowDefinitionThatDoesNotExistIsPoison() {
        // A creation naming a definition the engine has never loaded cannot succeed on any retry
        // (the definition is not going to appear). It throws a typed poison exception so the
        // delivery machinery parks it rather than retrying it forever — the previously untested path.
        String missing = "no-such-definition";
        when(workflowDefinitionRepository.findById(missing)).thenReturn(Optional.empty());

        CreateProcessCommand command = new CreateProcessCommand(
                "process-1", missing, "BK-1", List.of(), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> createProcessUseCase.handle(command))
                .isInstanceOf(io.mateu.workflow.application.out.UnknownWorkflowDefinitionException.class);

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void shouldCreateProcessBornPausedWhenTheDefinitionIsPaused() {
        // given
        String workflowDefinitionId = "wd-1";
        Step step = new Step("step-1", workflowDefinitionId, StepType.ACTION, "Step 1", "Desc", null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        WorkflowDefinition workflowDefinition = new WorkflowDefinition(
                workflowDefinitionId, "Test Workflow", 1, "Description",  false, 0, false, null, 0, List.of(step), true, false, false
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
    // --- the children of a PROCESS step -------------------------------------------------------

    private WorkflowDefinition childDefinition() {
        Step step = new Step("step-1", "wd-child", StepType.ACTION, "Step 1", "Desc", null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        return new WorkflowDefinition("wd-child", "Child", 1, "Description", false, 0, false, null, 0, List.of(step));
    }

    private CreateProcessCommand childCreation() {
        return new CreateProcessCommand(null, "wd-child", "parent:pse-1", List.of(), "pse-1");
    }

    private io.mateu.workflow.domain.aggregates.Process child(String key, ProcessStatus status) {
        return io.mateu.workflow.domain.aggregates.Process.builder().id("child-" + key).businessKey(key)
                .parentStepExecutionId("pse-1").status(status).variables(List.of()).build();
    }

    private void parentStepIs(StepExecutionStatus status) {
        when(stepExecutionRepository.findById("pse-1")).thenReturn(Optional.of(
                StepExecution.builder().id("pse-1").processId("p-parent").status(status).build()));
    }

    private String createdKey() {
        var saved = org.mockito.ArgumentCaptor.forClass(io.mateu.workflow.domain.aggregates.Process.class);
        verify(processRepository).save(saved.capture());
        return saved.getValue().getBusinessKey();
    }

    @Test
    void aPROCESSStepsFirstChildTakesTheBaseKey() {
        createProcessUseCase.maxProcessDepth = 20;
        when(workflowDefinitionRepository.findById("wd-child")).thenReturn(Optional.of(childDefinition()));

        createProcessUseCase.handle(childCreation());

        org.assertj.core.api.Assertions.assertThat(createdKey()).isEqualTo("parent:pse-1");
    }

    @Test
    void aChildStillInFlightIsNotStartedTwice() {
        // A redelivered creation, or a retry after a timeout: either way it re-attaches.
        when(processRepository.findByBusinessKey("parent:pse-1"))
                .thenReturn(Optional.of(child("parent:pse-1", ProcessStatus.RUNNING)));

        createProcessUseCase.handle(childCreation());

        verify(processRepository, never()).save(any());
    }

    @Test
    void aWaitingStepWhoseChildrenAllFinishedRunsTheChildAgainUnderTheNextKey() {
        createProcessUseCase.maxProcessDepth = 20;
        when(processRepository.findByBusinessKey("parent:pse-1"))
                .thenReturn(Optional.of(child("parent:pse-1", ProcessStatus.ERROR)));
        when(processRepository.findByBusinessKey("parent:pse-1#2"))
                .thenReturn(Optional.of(child("parent:pse-1#2", ProcessStatus.CANCELLED)));
        when(processRepository.findByBusinessKey("parent:pse-1#3")).thenReturn(Optional.empty());
        parentStepIs(StepExecutionStatus.PENDING);
        when(workflowDefinitionRepository.findById("wd-child")).thenReturn(Optional.of(childDefinition()));

        createProcessUseCase.handle(childCreation());

        org.assertj.core.api.Assertions.assertThat(createdKey()).isEqualTo("parent:pse-1#3");
    }

    @Test
    void aStaleCreationForAStepThatIsNoLongerWaitingStartsNothing() {
        when(processRepository.findByBusinessKey("parent:pse-1"))
                .thenReturn(Optional.of(child("parent:pse-1", ProcessStatus.COMPLETED)));
        when(processRepository.findByBusinessKey("parent:pse-1#2")).thenReturn(Optional.empty());
        parentStepIs(StepExecutionStatus.COMPLETED);

        createProcessUseCase.handle(childCreation());

        verify(processRepository, never()).save(any());
    }
}
