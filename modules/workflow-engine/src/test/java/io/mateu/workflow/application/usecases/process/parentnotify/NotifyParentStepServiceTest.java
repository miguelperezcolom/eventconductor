package io.mateu.workflow.application.usecases.process.parentnotify;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyParentStepServiceTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ObjectProvider<UpdateStepExecutionUseCase> updateStepExecutionUseCaseProvider;
    @Mock UpdateStepExecutionUseCase updateStepExecutionUseCase;

    @InjectMocks NotifyParentStepService service;

    @BeforeEach
    void wireProvider() {
        lenient().when(updateStepExecutionUseCaseProvider.getObject()).thenReturn(updateStepExecutionUseCase);
    }

    private Step processStep(List<String> outputVariables) {
        return new Step("spawn", "wd-parent", StepType.PROCESS, "Spawn", null, "start", null, null, false,
                null, null, null, "wd-child", outputVariables,
                0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private StepExecution parentStepExecution(StepExecutionStatus status, List<String> outputVariables) {
        return StepExecution.builder()
                .id("pse-1").processId("p-parent").workflowDefinitionId("wd-parent")
                .stepId("spawn").stepJson(JsonSerializer.toJson(processStep(outputVariables)))
                .status(status).variables(List.of()).build();
    }

    private Process child(ProcessStatus status, List<Variable> variables) {
        return Process.builder().id("p-child").businessKey("parent:pse-1")
                .parentStepExecutionId("pse-1").status(status).variables(variables).build();
    }

    @Test
    void childCompletedCompletesTheParentStepCopyingOnlyTheDeclaredOutputVariables() {
        when(stepExecutionRepository.findById("pse-1"))
                .thenReturn(Optional.of(parentStepExecution(StepExecutionStatus.PENDING, List.of("result"))));

        service.processReachedTerminalStatus(child(ProcessStatus.COMPLETED, List.of(
                new Variable("result", "42"),
                new Variable("secret", "stays-in-the-child"))));

        ArgumentCaptor<UpdateStepExecutionCommand> captor = ArgumentCaptor.forClass(UpdateStepExecutionCommand.class);
        verify(updateStepExecutionUseCase).handle(captor.capture());
        assertThat(captor.getValue().stepId()).isEqualTo("pse-1");
        assertThat(captor.getValue().status()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(captor.getValue().variables()).containsExactly(new Variable("result", "42"));
    }

    @Test
    void childCompletedWithoutOutputVariablesCopiesNothing() {
        when(stepExecutionRepository.findById("pse-1"))
                .thenReturn(Optional.of(parentStepExecution(StepExecutionStatus.PENDING, null)));

        service.processReachedTerminalStatus(child(ProcessStatus.COMPLETED, List.of(
                new Variable("result", "42"))));

        ArgumentCaptor<UpdateStepExecutionCommand> captor = ArgumentCaptor.forClass(UpdateStepExecutionCommand.class);
        verify(updateStepExecutionUseCase).handle(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(captor.getValue().variables()).isEmpty();
    }

    @Test
    void childErrorMarksTheParentStepAsError() {
        when(stepExecutionRepository.findById("pse-1"))
                .thenReturn(Optional.of(parentStepExecution(StepExecutionStatus.PENDING, List.of("result"))));

        service.processReachedTerminalStatus(child(ProcessStatus.ERROR, List.of()));

        ArgumentCaptor<UpdateStepExecutionCommand> captor = ArgumentCaptor.forClass(UpdateStepExecutionCommand.class);
        verify(updateStepExecutionUseCase).handle(captor.capture());
        assertThat(captor.getValue().stepId()).isEqualTo("pse-1");
        assertThat(captor.getValue().status()).isEqualTo(StepExecutionStatus.ERROR);
    }

    @Test
    void childCancelledMarksTheParentStepAsError() {
        when(stepExecutionRepository.findById("pse-1"))
                .thenReturn(Optional.of(parentStepExecution(StepExecutionStatus.PENDING, List.of())));

        service.processReachedTerminalStatus(child(ProcessStatus.CANCELLED, List.of()));

        ArgumentCaptor<UpdateStepExecutionCommand> captor = ArgumentCaptor.forClass(UpdateStepExecutionCommand.class);
        verify(updateStepExecutionUseCase).handle(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(StepExecutionStatus.ERROR);
    }

    @Test
    void parentStepAlreadyOutOfItsWaitIsLeftAlone() {
        // Idempotency: a late or redelivered notification must not resurrect a step that
        // already left PENDING (earlier notification, timeout, cancellation, ...).
        when(stepExecutionRepository.findById("pse-1"))
                .thenReturn(Optional.of(parentStepExecution(StepExecutionStatus.COMPLETED, List.of("result"))));

        service.processReachedTerminalStatus(child(ProcessStatus.COMPLETED, List.of(
                new Variable("result", "42"))));

        verify(updateStepExecutionUseCase, never()).handle(any());
    }

    @Test
    void topLevelProcessDoesNotNotifyAnybody() {
        var topLevel = Process.builder().id("p-1").status(ProcessStatus.COMPLETED)
                .variables(List.of()).build();

        service.processReachedTerminalStatus(topLevel);

        verify(stepExecutionRepository, never()).findById(any());
        verify(updateStepExecutionUseCase, never()).handle(any());
    }

    @Test
    void unknownParentStepExecutionIsANoOp() {
        when(stepExecutionRepository.findById("pse-1")).thenReturn(Optional.empty());

        service.processReachedTerminalStatus(child(ProcessStatus.COMPLETED, List.of()));

        verify(updateStepExecutionUseCase, never()).handle(any());
    }

    @Test
    void nonTerminalChildStatusDoesNotTouchTheParentStep() {
        when(stepExecutionRepository.findById("pse-1"))
                .thenReturn(Optional.of(parentStepExecution(StepExecutionStatus.PENDING, List.of())));

        service.processReachedTerminalStatus(child(ProcessStatus.RUNNING, List.of()));

        verify(updateStepExecutionUseCase, never()).handle(any());
    }
}
