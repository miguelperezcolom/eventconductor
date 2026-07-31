package io.mateu.workflow.application.usecases.process.childcancel;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelChildProcessServiceTest {

    @Mock ProcessRepository processRepository;
    @Mock ObjectProvider<CancelProcessUseCase> cancelProcessUseCaseProvider;
    @Mock CancelProcessUseCase cancelProcessUseCase;

    @InjectMocks CancelChildProcessService service;

    @BeforeEach
    void wireProvider() {
        lenient().when(cancelProcessUseCaseProvider.getObject()).thenReturn(cancelProcessUseCase);
    }

    private Step step(StepType type) {
        return new Step("spawn", "wd-parent", type, "Spawn", null, "start", null, null, false,
                StepType.ACTION.equals(type) ? "topic" : null, null, null,
                StepType.PROCESS.equals(type) ? "wd-child" : null, null,
                0, null, null, null, null, 0, 0, false, null, 0);
    }

    private StepExecution stepExecution(StepType type, StepExecutionStatus status) {
        return StepExecution.builder()
                .id("pse-1").processId("p-parent").workflowDefinitionId("wd-parent")
                .stepId("spawn").stepJson(JsonSerializer.toJson(step(type)))
                .status(status).variables(List.of()).build();
    }

    private Process child(ProcessStatus status) {
        return Process.builder().id("p-child").businessKey("parent:pse-1")
                .parentStepExecutionId("pse-1").status(status).variables(List.of()).build();
    }

    @ParameterizedTest
    @EnumSource(value = StepExecutionStatus.class, names = {"CANCELLED", "ERROR", "TIMEOUT"})
    void nonCompletedTerminalProcessStepCancelsItsPendingChild(StepExecutionStatus status) {
        when(processRepository.findByBusinessKey("parent:pse-1"))
                .thenReturn(Optional.of(child(ProcessStatus.PENDING)));

        service.stepReachedTerminalStatus(stepExecution(StepType.PROCESS, status));

        verify(cancelProcessUseCase).handle(new CancelProcessCommand("p-child"));
    }

    @Test
    void cancelsARunningChild() {
        when(processRepository.findByBusinessKey("parent:pse-1"))
                .thenReturn(Optional.of(child(ProcessStatus.RUNNING)));

        service.stepReachedTerminalStatus(stepExecution(StepType.PROCESS, StepExecutionStatus.CANCELLED));

        verify(cancelProcessUseCase).handle(new CancelProcessCommand("p-child"));
    }

    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class, names = {"COMPLETED", "CANCELLED", "ERROR"})
    void childAlreadyTerminalIsLeftAlone(ProcessStatus childStatus) {
        when(processRepository.findByBusinessKey("parent:pse-1"))
                .thenReturn(Optional.of(child(childStatus)));

        service.stepReachedTerminalStatus(stepExecution(StepType.PROCESS, StepExecutionStatus.CANCELLED));

        verify(cancelProcessUseCase, never()).handle(any());
    }

    @Test
    void absentChildIsANoOp() {
        when(processRepository.findByBusinessKey("parent:pse-1")).thenReturn(Optional.empty());

        service.stepReachedTerminalStatus(stepExecution(StepType.PROCESS, StepExecutionStatus.ERROR));

        verify(cancelProcessUseCase, never()).handle(any());
    }

    @Test
    void nonProcessStepNeverLooksUpAChild() {
        service.stepReachedTerminalStatus(stepExecution(StepType.ACTION, StepExecutionStatus.CANCELLED));

        verify(processRepository, never()).findByBusinessKey(any());
        verify(cancelProcessUseCase, never()).handle(any());
    }

    @ParameterizedTest
    @EnumSource(value = StepExecutionStatus.class, names = {"CREATED", "PENDING", "RUNNING", "COMPLETED"})
    void otherStepStatusesNeverLookUpAChild(StepExecutionStatus status) {
        service.stepReachedTerminalStatus(stepExecution(StepType.PROCESS, status));

        verify(processRepository, never()).findByBusinessKey(any());
        verify(cancelProcessUseCase, never()).handle(any());
    }

    @Test
    void stepWithoutStepJsonIsANoOp() {
        var bare = StepExecution.builder().id("pse-1").processId("p-parent")
                .status(StepExecutionStatus.CANCELLED).variables(List.of()).build();

        service.stepReachedTerminalStatus(bare);

        verify(processRepository, never()).findByBusinessKey(any());
    }

    @Test
    void nullStepExecutionIsANoOp() {
        service.stepReachedTerminalStatus(null);

        verify(processRepository, never()).findByBusinessKey(any());
    }
}
