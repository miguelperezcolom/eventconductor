package io.mateu.workflow.application.usecases.stepexecution.retry;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryStepExecutionUseCaseTest {

    @Mock
    StepExecutionRepository stepExecutionRepository;
    @Mock
    ProcessRepository processRepository;
    @Mock
    StepOverProcessUseCase stepOverProcessUseCase;
    @Mock
    WorkflowMetrics workflowMetrics;

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks
    RetryStepExecutionUseCase retryStepExecutionUseCase;

    @Test
    void shouldRetryStepExecutionInError() {
        // given
        String processId = "process-1";
        Process process = Process.builder()
                .id(processId)
                .status(ProcessStatus.ERROR)
                .build();

        StepExecution stepExecution = StepExecution.builder()
                .id("se-1")
                .processId(processId)
                .status(StepExecutionStatus.ERROR)
                .build();

        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(stepExecution));
        when(processRepository.findById(processId)).thenReturn(Optional.of(process));

        // when
        retryStepExecutionUseCase.handle(new RetryStepExecutionCommand("se-1"));

        // then
        verify(stepExecutionRepository).save(any(StepExecution.class));
        verify(processRepository).save(any(Process.class));
        verify(stepOverProcessUseCase).handle(any(StepOverProcessCommand.class));
        verify(workflowMetrics).retryPerformed(any(), eq(WorkflowMetrics.RetryTrigger.MANUAL));
    }

    @Test
    void shouldNotRetryStepExecutionNotInError() {
        // given
        StepExecution stepExecution = StepExecution.builder()
                .id("se-1")
                .processId("process-1")
                .status(StepExecutionStatus.COMPLETED)
                .build();

        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(stepExecution));

        // when
        retryStepExecutionUseCase.handle(new RetryStepExecutionCommand("se-1"));

        // then
        verify(stepExecutionRepository, never()).save(any(StepExecution.class));
        verify(processRepository, never()).save(any(Process.class));
        verify(stepOverProcessUseCase, never()).handle(any(StepOverProcessCommand.class));
        verify(workflowMetrics, never()).retryPerformed(any(), any());
    }
}
