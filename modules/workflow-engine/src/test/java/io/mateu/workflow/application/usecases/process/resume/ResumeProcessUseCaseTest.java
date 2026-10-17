package io.mateu.workflow.application.usecases.process.resume;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessStepExecutionUpdateCommand;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeProcessUseCaseTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock StepOverProcessUseCase stepOverProcessUseCase;
    @Mock ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;
    @Mock io.mateu.workflow.application.out.ProcessLockService processLockService;

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks ResumeProcessUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void lockIsGranted() {
        org.mockito.Mockito.lenient().when(processLockService.runExclusively(org.mockito.ArgumentMatchers.eq("p-1"), any()))
                .thenAnswer(io.mateu.workflow.support.RunsTheAction.granted());
    }

    private Process pausedProcess(LocalDateTime pausedAt) {
        return Process.builder().id("p-1").status(ProcessStatus.PAUSED).pausedAt(pausedAt).build();
    }

    private StepExecution se(String id, StepExecutionStatus status, LocalDateTime startedAt) {
        return StepExecution.builder().id(id).processId("p-1").status(status).startedAt(startedAt).build();
    }

    @Test
    void resumesAndShiftsInFlightStepClocksByThePauseDuration() {
        var pausedAt = LocalDateTime.now().minusMinutes(10);
        var process = pausedProcess(pausedAt);
        var startedAt = LocalDateTime.now().minusMinutes(30);
        var pending = se("se-pending", StepExecutionStatus.PENDING, startedAt);
        var running = se("se-running", StepExecutionStatus.RUNNING, startedAt);
        var completed = se("se-completed", StepExecutionStatus.COMPLETED, startedAt);
        var created = se("se-created", StepExecutionStatus.CREATED, null);
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process))
                .thenReturn(List.of(pending, running, completed, created));

        useCase.handle(new ResumeProcessCommand("p-1"));

        // Only the non-terminal steps with a startedAt get their clock shifted — by ~10 minutes.
        var seCaptor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository, org.mockito.Mockito.times(2)).save(seCaptor.capture());
        assertThat(seCaptor.getAllValues()).extracting(StepExecution::id)
                .containsExactlyInAnyOrder("se-pending", "se-running");
        seCaptor.getAllValues().forEach(shifted ->
                assertThat(shifted.getStartedAt()).isCloseTo(startedAt.plusMinutes(10),
                        within(5, java.time.temporal.ChronoUnit.SECONDS)));

        var pCaptor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(pCaptor.capture());
        assertThat(pCaptor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(pCaptor.getValue().getPausedAt()).isNull();

        var logCaptor = ArgumentCaptor.forClass(LogMessage.class);
        verify(logMessageRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getMessage()).startsWith("Process resumed after ");

        // And the flow is driven forward.
        verify(stepOverProcessUseCase).handle(new StepOverProcessCommand("p-1"));
        verify(processUpdateStepExecutionUpdateUseCase).handle(new ProcessStepExecutionUpdateCommand("p-1"));
    }

    @Test
    void nullPausedAtResumesWithZeroShift() {
        var process = pausedProcess(null);
        var startedAt = LocalDateTime.now().minusMinutes(30);
        var pending = se("se-pending", StepExecutionStatus.PENDING, startedAt);
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(pending));

        useCase.handle(new ResumeProcessCommand("p-1"));

        var seCaptor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(seCaptor.capture());
        assertThat(seCaptor.getValue().getStartedAt()).isEqualTo(startedAt);

        var pCaptor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(pCaptor.capture());
        assertThat(pCaptor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class, names = {"PENDING", "RUNNING", "COMPLETED", "CANCELLED", "ERROR"})
    void doesNothingWhenNotPaused(ProcessStatus from) {
        when(processRepository.findById("p-1")).thenReturn(Optional.of(
                Process.builder().id("p-1").status(from).build()));

        useCase.handle(new ResumeProcessCommand("p-1"));

        verify(processRepository, never()).save(any());
        verify(stepOverProcessUseCase, never()).handle(any());
        verify(processUpdateStepExecutionUpdateUseCase, never()).handle(any());
    }

    @Test
    void resumesUnderTheProcessLock() {
        // Everything — the PAUSED check, the clock shift, the status change and the step-over —
        // runs inside the lock: nothing is read or written before it is held.
        org.mockito.Mockito.reset(processLockService);
        var process = pausedProcess(LocalDateTime.now().minusMinutes(1));
        var inLock = new java.util.concurrent.atomic.AtomicBoolean();
        when(processLockService.runExclusively(org.mockito.ArgumentMatchers.eq("p-1"), any())).thenAnswer(invocation -> {
            inLock.set(true);
            try {
                invocation.getArgument(1, Runnable.class).run();
            } finally {
                inLock.set(false);
            }
            return true;
        });
        when(processRepository.findById("p-1")).thenAnswer(invocation -> {
            assertThat(inLock).as("the process is read only while its lock is held").isTrue();
            return Optional.of(process);
        });
        when(processRepository.save(any())).thenAnswer(invocation -> {
            assertThat(inLock).as("the process is written only while its lock is held").isTrue();
            return "p-1";
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(inLock).as("the flow is driven forward inside the same lock").isTrue();
            return null;
        }).when(stepOverProcessUseCase).handle(any());

        useCase.handle(new ResumeProcessCommand("p-1"));

        verify(processRepository).save(any());
        verify(stepOverProcessUseCase).handle(new StepOverProcessCommand("p-1"));
    }

    @Test
    void aResumeThatCannotTakeTheLockFailsInsteadOfDoingNothingSilently() {
        org.mockito.Mockito.reset(processLockService);
        when(processLockService.runExclusively(org.mockito.ArgumentMatchers.eq("p-1"), any())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.handle(new ResumeProcessCommand("p-1")))
                .isInstanceOf(io.mateu.workflow.application.out.ConcurrentProcessAccessException.class);

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
    }
}
