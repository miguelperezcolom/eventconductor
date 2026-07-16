package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepOverProcessUseCaseTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessLockService lockService;
    @Mock WorkflowMetrics workflowMetrics;

    @InjectMocks StepOverProcessUseCase useCase;

    @BeforeEach
    void allowLock() {
        when(lockService.tryLock(any())).thenReturn(true);
    }

    private Process process(String id) {
        return Process.builder().id(id).status(ProcessStatus.RUNNING)
                .variables(List.of()).build();
    }

    private StepExecution se(String id, String stepId, StepType type, StepExecutionStatus status, int order) {
        Step step = new Step(stepId, "wd-1", type, stepId, null, null, null, false, "topic", "form-1", null, null, 0, null, null, null, 0, 0, false, null);
        return StepExecution.builder()
                .id(id).processId("p-1").workflowDefinitionId("wd-1")
                .stepId(stepId).stepJson(JsonSerializer.toJson(step))
                .status(status).order(order).variables(List.of()).build();
    }

    @Test
    void completedStepsAreSkipped() {
        var process = process("p-1");
        var completed = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.COMPLETED, 0);
        var pending = se("se-2", "step-2", StepType.ACTION, StepExecutionStatus.CREATED, 1);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed, pending));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void endStepCompletesProcess() {
        var process = process("p-1");
        var endStep = se("se-1", "end", StepType.END, StepExecutionStatus.CREATED, 0);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(endStep));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(captor.getValue().getCompletionPercentage()).isEqualTo(100);
        verify(workflowMetrics).processCompleted(any(), any());
    }

    @Test
    void pendingStepBlocksFurtherSteps() {
        var process = process("p-1");
        var pending = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.PENDING, 0);
        var created = se("se-2", "step-2", StepType.ACTION, StepExecutionStatus.CREATED, 1);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(pending, created));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void emptyExecutableStepsWithNoRemainingCompletesProcess() {
        var process = process("p-1");
        var completed = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.COMPLETED, 0);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void alreadyCancelledProcessIsNotOverwritten() {
        var process = Process.builder().id("p-1").status(ProcessStatus.CANCELLED)
                .variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void errorStepBlocksFlowAndMarksProcessAsError() {
        var process = process("p-1");
        var failed = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.ERROR, 0);
        var next = se("se-2", "step-2", StepType.ACTION, StepExecutionStatus.CREATED, 1);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(failed, next));

        useCase.handle(new StepOverProcessCommand("p-1"));

        // Successors of a failed step must not run, and the process must be flagged
        // as failed — never falsely completed.
        verify(stepExecutionRepository, never()).save(any());
        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.ERROR);
        verify(workflowMetrics).processErrored(any(), any());
        verify(workflowMetrics, never()).processCompleted(any(), any());
    }


    @Test
    void parallelStepsAreAllStarted() {
        var process = process("p-1");
        Step step1 = new Step("s1", "wd-1", StepType.ACTION, "s1", null, null, null, true, "topic", null, null, null, 0, null, null, null, 0, 0, false, null);
        Step step2 = new Step("s2", "wd-1", StepType.ACTION, "s2", null, null, null, true, "topic", null, null, null, 0, null, null, null, 0, 0, false, null);
        var se1 = StepExecution.builder().id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("s1").stepJson(JsonSerializer.toJson(step1))
                .status(StepExecutionStatus.CREATED).order(0).variables(List.of()).build();
        var se2 = StepExecution.builder().id("se-2").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("s2").stepJson(JsonSerializer.toJson(step2))
                .status(StepExecutionStatus.CREATED).order(1).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se1, se2));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(stepExecutionRepository, times(2)).save(any());
    }

    @Test
    void stepWithUnmetPreconditionStepIdIsNotStarted() {
        var process = process("p-1");
        Step prerequisite = new Step("prereq", "wd-1", StepType.ACTION, "prereq", null, null, null, false, "topic", null, null, null, 0, null, null, null, 0, 0, false, null);
        Step dependent = new Step("dep", "wd-1", StepType.ACTION, "dep", null, "prereq", null, false, "topic", null, null, null, 0, null, null, null, 0, 0, false, null);
        var sePrereq = StepExecution.builder().id("se-prereq").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("prereq").stepJson(JsonSerializer.toJson(prerequisite))
                .status(StepExecutionStatus.CREATED).order(0).variables(List.of()).build();
        var seDependent = StepExecution.builder().id("se-dep").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("dep").stepJson(JsonSerializer.toJson(dependent))
                .status(StepExecutionStatus.CREATED).order(1).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(sePrereq, seDependent));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStepId()).isEqualTo("prereq");
    }
}
