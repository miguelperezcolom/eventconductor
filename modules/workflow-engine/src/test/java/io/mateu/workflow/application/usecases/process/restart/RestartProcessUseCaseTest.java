package io.mateu.workflow.application.usecases.process.restart;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestartProcessUseCaseTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock StepOverProcessUseCase stepOverProcessUseCase;
    @Mock WorkflowMetrics workflowMetrics;

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks RestartProcessUseCase restartProcessUseCase;

    private StepExecution step(String id, int order, StepExecutionStatus status,
                               LocalDateTime startedAt, List<Variable> variables) {
        return StepExecution.builder()
                .id(id).stepId(id).order(order).status(status)
                .startedAt(startedAt).finishedAt(LocalDateTime.now())
                .attemptCount(3).deadlineAt(LocalDateTime.now())
                .awaitingMessageName("some-message").awaitingCorrelationKey("k")
                .variables(variables)
                .build();
    }

    @Test
    void putsEveryStepBackIncludingTheOnesThatSucceeded() {
        var process = Process.builder().id("p-1").status(ProcessStatus.ERROR)
                .variables(List.of(new Variable("total", "99"))).build();
        var done = step("s1", 1, StepExecutionStatus.COMPLETED, LocalDateTime.now(), List.of());
        var failed = step("s2", 2, StepExecutionStatus.ERROR, LocalDateTime.now(), List.of());
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(done, failed));

        restartProcessUseCase.handle(new RestartProcessCommand("p-1"));

        // Both back to CREATED, and back to looking like they never ran: a step that kept its
        // attempt count or its deadline would not be starting from the beginning.
        for (var stepExecution : List.of(done, failed)) {
            assertThat(stepExecution.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
            assertThat(stepExecution.getStartedAt()).isNull();
            assertThat(stepExecution.getFinishedAt()).isNull();
            assertThat(stepExecution.getAttemptCount()).isZero();
            assertThat(stepExecution.getDeadlineAt()).isNull();
            assertThat(stepExecution.getAwaitingMessageName()).isNull();
        }
        verify(stepExecutionRepository, times(2)).save(any(StepExecution.class));
        verify(stepOverProcessUseCase).handle(any(StepOverProcessCommand.class));
        verify(workflowMetrics).retryPerformed(any(), eq(WorkflowMetrics.RetryTrigger.MANUAL));
    }

    @Test
    void restoresTheVariablesTheProcessWasStartedWith() {
        // The first step froze the process's variables as they were at the start; everything after
        // that is what the failed run wrote. Re-running from the latter is not "from the beginning"
        // — a guard reading a variable a later step wrote would branch differently the second time.
        var process = Process.builder().id("p-2").status(ProcessStatus.ERROR)
                .variables(List.of(new Variable("orderId", "A-1"), new Variable("charged", "true")))
                .build();
        var first = step("s1", 1, StepExecutionStatus.COMPLETED, LocalDateTime.now().minusMinutes(5),
                List.of(new Variable("orderId", "A-1")));
        var second = step("s2", 2, StepExecutionStatus.ERROR, LocalDateTime.now(),
                List.of(new Variable("orderId", "A-1"), new Variable("charged", "true")));
        when(processRepository.findById("p-2")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(second, first));

        restartProcessUseCase.handle(new RestartProcessCommand("p-2"));

        var saved = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(saved.capture());
        assertThat(saved.getValue().getVariables())
                .extracting(Variable::name).containsExactly("orderId");
        assertThat(saved.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(saved.getValue().getFinished()).isNull();
        assertThat(saved.getValue().getCompletionPercentage()).isZero();
    }

    @Test
    void keepsTheVariablesItHasWhenNoStepEverStarted() {
        var process = Process.builder().id("p-3").status(ProcessStatus.CANCELLED)
                .variables(List.of(new Variable("orderId", "A-9"))).build();
        var neverStarted = step("s1", 1, StepExecutionStatus.CANCELLED, null, List.of());
        when(processRepository.findById("p-3")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(neverStarted));

        restartProcessUseCase.handle(new RestartProcessCommand("p-3"));

        var saved = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(saved.capture());
        assertThat(saved.getValue().getVariables())
                .extracting(Variable::name).containsExactly("orderId");
    }

    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class,
            names = {"PENDING", "RUNNING", "PAUSED", "COMPLETED", "COMPENSATED"})
    void refusesToRestartAProcessThatIsNotStopped(ProcessStatus status) {
        // This one matters more than its retry twin: restarting a RUNNING or COMPLETED process
        // would re-run work that is either in flight or finished.
        var process = Process.builder().id("p-4").status(status).build();
        when(processRepository.findById("p-4")).thenReturn(Optional.of(process));

        restartProcessUseCase.handle(new RestartProcessCommand("p-4"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
        verify(stepOverProcessUseCase, never()).handle(any());
    }
}
