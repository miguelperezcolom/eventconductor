package io.mateu.workflow.application.usecases.checktimer;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.checktimer.completetimerstep.CompleteTimerStepHandler;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckTimerUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock CompleteTimerStepHandler completeTimerStepHandler;

    @InjectMocks CheckTimerUseCase useCase;

    private Step timerStep(long durationMillis, String untilVariable) {
        return new Step("s1", "wd-1", StepType.TIMER, "Wait", null, null, null, null, false, null, null, null, null, null, durationMillis, untilVariable, null, null, null, 0, 0, false, null, 0, null);
    }

    private StepExecution pendingSe(Step step, LocalDateTime startedAt, Variable... variables) {
        return StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(startedAt)
                .variables(List.of(variables))
                .build();
    }

    @Test
    void triggersCompletionForDueTimer() {
        var due = pendingSe(timerStep(100, null), LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(due));

        useCase.handle(new CheckTimerCommand("p-1"));

        verify(completeTimerStepHandler).handle(any());
    }

    @Test
    void triggersCompletionForDueDateVariableTimer() {
        var due = pendingSe(timerStep(0, "resumeAt"), LocalDateTime.now().minusSeconds(10),
                new Variable("resumeAt", LocalDateTime.now().minusSeconds(1).toString()));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(due));

        useCase.handle(new CheckTimerCommand("p-1"));

        verify(completeTimerStepHandler).handle(any());
    }

    @Test
    void doesNotTriggerCompletionForTimerNotYetDue() {
        var notDue = pendingSe(timerStep(Long.MAX_VALUE, null), LocalDateTime.now());
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(notDue));

        useCase.handle(new CheckTimerCommand("p-1"));

        verify(completeTimerStepHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCompletionForNonTimerStep() {
        var action = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var se = pendingSe(action, LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(se));

        useCase.handle(new CheckTimerCommand("p-1"));

        verify(completeTimerStepHandler, never()).handle(any());
    }

    @Test
    void looksUpOnlyTheStepsOfTheCommandedProcess() {
        // The scoping is the query, not an in-memory filter: loading every live step in the
        // system and filtering by process turns one scheduler tick into a full table load per
        // due process.
        useCase.handle(new CheckTimerCommand("other-process"));

        verify(stepExecutionRepository).findPendingOrRunningByProcessId("other-process");
        verify(stepExecutionRepository, never()).findPendingOrRunning();
        verify(completeTimerStepHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCompletionForMisconfiguredTimer() {
        // The date variable is missing: start() already failed such steps; the scan skips them.
        var due = pendingSe(timerStep(0, "resumeAt"), LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(due));

        useCase.handle(new CheckTimerCommand("p-1"));

        verify(completeTimerStepHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCompletionForTimerWithNullStartedAt() {
        var se = pendingSe(timerStep(100, null), null);
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(se));

        useCase.handle(new CheckTimerCommand("p-1"));

        verify(completeTimerStepHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCompletionForDueTimerOfPausedProcess() {
        var due = pendingSe(timerStep(100, null), LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(due));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(
                Process.builder().id("p-1").status(ProcessStatus.PAUSED).build()));

        useCase.handle(new CheckTimerCommand("p-1"));

        // The paused process freezes the timer clock — the due moment must not fire.
        verify(completeTimerStepHandler, never()).handle(any());
    }

    @Test
    void onlyLooksUpTheProcessWhenATimerWouldFire() {
        var notDue = pendingSe(timerStep(Long.MAX_VALUE, null), LocalDateTime.now());
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(notDue));

        useCase.handle(new CheckTimerCommand("p-1"));

        verifyNoInteractions(processRepository);
    }
}
