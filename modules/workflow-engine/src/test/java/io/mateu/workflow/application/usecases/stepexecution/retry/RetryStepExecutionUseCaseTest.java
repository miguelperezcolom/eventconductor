package io.mateu.workflow.application.usecases.stepexecution.retry;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.support.TrackedProcessLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock ProcessLockService processLockService;

    @InjectMocks
    RetryStepExecutionUseCase retryStepExecutionUseCase;

    final TrackedProcessLock lock = new TrackedProcessLock();

    @BeforeEach
    void lockIsGranted() {
        lenient().when(processLockService.runExclusively(anyString(), any())).thenAnswer(lock.granted());
    }

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

    @Test
    void retriesTheStepUnderItsProcessLock() {
        // The step is re-read inside the lock: the status check that decides the retry, the
        // revival and the step-over are one unit.
        var process = Process.builder().id("p-1").status(ProcessStatus.ERROR).build();
        var failed = StepExecution.builder().id("se-1").processId("p-1").status(StepExecutionStatus.ERROR).build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(failed));
        when(processRepository.findById("p-1")).thenAnswer(invocation -> {
            lock.assertHeld("the process is read");
            return Optional.of(process);
        });
        when(stepExecutionRepository.save(any())).thenAnswer(invocation -> {
            lock.assertHeld("the step is written");
            return "se-1";
        });
        when(processRepository.save(any())).thenAnswer(invocation -> {
            lock.assertHeld("the process is written");
            return "p-1";
        });
        doAnswer(invocation -> {
            lock.assertHeld("the flow is driven forward");
            return null;
        }).when(stepOverProcessUseCase).handle(any());

        retryStepExecutionUseCase.handle(new RetryStepExecutionCommand("se-1"));

        verify(processLockService).runExclusively(eq("p-1"), any());
        verify(stepExecutionRepository, times(2)).findById("se-1");
        verify(stepOverProcessUseCase).handle(new StepOverProcessCommand("p-1"));
    }

    @Test
    void aStepRetryThatCannotTakeTheLockFailsInsteadOfDoingNothingSilently() {
        reset(processLockService);
        TrackedProcessLock.refused(processLockService);
        var failed = StepExecution.builder().id("se-1").processId("p-1").status(StepExecutionStatus.ERROR).build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> retryStepExecutionUseCase.handle(new RetryStepExecutionCommand("se-1")))
                .isInstanceOf(ConcurrentProcessAccessException.class);

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
        verifyNoInteractions(stepOverProcessUseCase);
    }
}
