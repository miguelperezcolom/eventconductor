package io.mateu.workflow.application.usecases.process.retry;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryProcessUseCaseTest {

    @Mock
    ProcessRepository processRepository;
    @Mock
    StepExecutionRepository stepExecutionRepository;
    @Mock
    StepOverProcessUseCase stepOverProcessUseCase;
    @Mock
    WorkflowMetrics workflowMetrics;

    @InjectMocks
    RetryProcessUseCase retryProcessUseCase;

    @Test
    void shouldRetryProcess() {
        // given
        String processId = "process-1";
        Process process = Process.builder()
                .id(processId)
                .status(ProcessStatus.ERROR)
                .build();
        
        StepExecution stepExecution = StepExecution.builder()
                .id("se-1")
                .status(StepExecutionStatus.ERROR)
                .build();

        when(processRepository.findById(processId)).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(stepExecution));

        // when
        retryProcessUseCase.handle(new RetryProcessCommand(processId));

        // then
        verify(stepExecutionRepository).save(any(StepExecution.class));
        verify(processRepository).save(any(Process.class));
        verify(stepOverProcessUseCase).handle(any(StepOverProcessCommand.class));
        verify(workflowMetrics).retryPerformed(any(), eq(WorkflowMetrics.RetryTrigger.MANUAL));
    }

    /**
     * A cancelled process stopped without finishing, and picking it up again is a normal operator
     * move. What "where it stopped" means there is the cancelled steps — cancellation is what
     * every unfinished step was set to — while the ones that had already succeeded stay done.
     */
    @Test
    void revivesTheCancelledStepsOfACancelledProcess() {
        var process = Process.builder().id("process-2").status(ProcessStatus.CANCELLED).build();
        var cancelled = StepExecution.builder().id("se-cancelled")
                .status(StepExecutionStatus.CANCELLED).build();
        var completed = StepExecution.builder().id("se-completed")
                .status(StepExecutionStatus.COMPLETED).build();
        when(processRepository.findById("process-2")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(cancelled, completed));

        retryProcessUseCase.handle(new RetryProcessCommand("process-2"));

        assertThat(cancelled.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(completed.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        verify(stepExecutionRepository, times(1)).save(any(StepExecution.class));
        verify(stepOverProcessUseCase).handle(any(StepOverProcessCommand.class));
    }

    /**
     * A cancelled step in a process that merely failed is not the failure — an END step cancels
     * its live siblings, for one — so a retry from failure leaves it where it is.
     */
    @Test
    void leavesCancelledStepsAloneWhenTheProcessOnlyFailed() {
        var process = Process.builder().id("process-3").status(ProcessStatus.ERROR).build();
        var cancelled = StepExecution.builder().id("se-cancelled")
                .status(StepExecutionStatus.CANCELLED).build();
        var failed = StepExecution.builder().id("se-failed")
                .status(StepExecutionStatus.ERROR).build();
        when(processRepository.findById("process-3")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(cancelled, failed));

        retryProcessUseCase.handle(new RetryProcessCommand("process-3"));

        assertThat(failed.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(cancelled.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    /**
     * The list applies this to whatever the operator ticked, and the request also arrives from MCP
     * and REST, so refusing the statuses that make no sense belongs here rather than in a button.
     */
    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class,
            names = {"PENDING", "RUNNING", "PAUSED", "COMPLETED", "COMPENSATED"})
    void refusesToRetryAProcessThatIsNotStopped(ProcessStatus status) {
        var process = Process.builder().id("process-4").status(status).build();
        when(processRepository.findById("process-4")).thenReturn(Optional.of(process));

        retryProcessUseCase.handle(new RetryProcessCommand("process-4"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
        verify(stepOverProcessUseCase, never()).handle(any());
    }
}
