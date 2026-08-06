package io.mateu.workflow.infra.in.startup;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The boot-time rearm, which nothing exercised.
 *
 * <p>What it protects against is invisibility rather than failure. The engine finds work by
 * querying a step's materialised deadline and message subscription, so a step that started under a
 * version that stored neither is not merely un-timed-out: it is never looked at again. Its TIMER
 * never fires, its timeout never expires, no message ever reaches it, and the process stops for
 * good in total silence — nothing errors, nothing alerts, it simply stays RUNNING for ever.
 *
 * <p>The three properties below are the ones that make it safe to run at every boot, on every pod,
 * of a cluster that is already working: it must do nothing when there is nothing to do, it must
 * take the process lock before writing, and it must never be able to fail a boot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InFlightStepRearmRunnerTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock ProcessLockService processLockService;

    private InFlightStepRearmRunner runner() {
        return new InFlightStepRearmRunner(stepExecutionRepository, processRepository, processLockService);
    }

    private static Step stepWithTimeout(long timeoutMillis) {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t",
                null, null, null, null, 0, null, null, null, null, timeoutMillis, 0, false, null, 0, null);
    }

    /** A step as an older version left it: started, but carrying none of the derived lookup state. */
    private static StepExecution unarmed(String id, String processId, long timeoutMillis) {
        return StepExecution.builder()
                .id(id)
                .processId(processId)
                .stepJson(JsonSerializer.toJson(stepWithTimeout(timeoutMillis)))
                .status(StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now().minusMinutes(1))
                .build();
    }

    private static StepExecution armed(String id, String processId, Process process) {
        return unarmed(id, processId, 60_000).rearmedFor(process);
    }

    private static Process process(String id) {
        return Process.builder().id(id).variables(List.of()).build();
    }

    private void lockGrants() {
        when(processLockService.runExclusively(anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    /**
     * The steady state, which is every boot after the first: one query, no locks, no writes. This
     * runs on every pod of a live cluster, so "nothing to do" has to cost nothing and — more to the
     * point — must not take a single process lock away from the pods doing real work.
     */
    @Test
    void aClusterWithNothingToArmTakesNoLocksAndWritesNothing() {
        var process = process("p-1");
        when(stepExecutionRepository.findPendingOrRunning())
                .thenReturn(List.of(armed("se-1", "p-1", process)));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));

        runner().rearmOnce();

        verifyNoInteractions(processLockService);
        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void aStepLeftWithoutItsDeadlineIsArmedFromTheStateItAlreadyCarries() {
        var process = process("p-1");
        var stale = unarmed("se-1", "p-1", 60_000);
        assertThat(stale.getDeadlineAt()).isNull();

        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(stale));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(stale));
        lockGrants();

        runner().rearmOnce();

        var saved = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(saved.capture());
        assertThat(saved.getValue().getDeadlineAt())
                .isEqualTo(stale.getStartedAt().plusMinutes(1));
    }

    /**
     * Writes go through the lock, and the step is re-read under it. On a multi-pod cluster another
     * pod may be mid-flight on the same process, and saving the copy read during the survey pass
     * would roll its status back.
     */
    @Test
    void theWriteHappensUnderTheProcessLockAndOnAFreshlyReadStep() {
        var process = process("p-1");
        var surveyed = unarmed("se-1", "p-1", 60_000);
        var reRead = unarmed("se-1", "p-1", 30_000);

        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(surveyed));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(reRead));
        lockGrants();

        runner().rearmOnce();

        verify(processLockService).runExclusively(org.mockito.ArgumentMatchers.eq("p-1"), any());
        var saved = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(saved.capture());
        // 30s from the re-read step, not the 60s the survey pass saw.
        assertThat(saved.getValue().getDeadlineAt())
                .isEqualTo(reRead.getStartedAt().plusSeconds(30));
    }

    /** A process another pod is holding is left for the next start, not forced and not failed. */
    @Test
    void aProcessThatCannotBeLockedIsSkippedRatherThanForced() {
        var process = process("p-1");
        when(stepExecutionRepository.findPendingOrRunning())
                .thenReturn(List.of(unarmed("se-1", "p-1", 60_000)));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(processLockService.runExclusively(anyString(), any())).thenReturn(false);

        runner().rearmOnce();

        verify(stepExecutionRepository, never()).save(any());
    }

    /** A step whose process is gone has nothing to derive a deadline from. */
    @Test
    void aStepWhoseProcessIsGoneIsIgnored() {
        when(stepExecutionRepository.findPendingOrRunning())
                .thenReturn(List.of(unarmed("se-1", "p-gone", 60_000)));
        when(processRepository.findById("p-gone")).thenReturn(Optional.empty());

        runner().rearmOnce();

        verifyNoInteractions(processLockService);
        verify(stepExecutionRepository, never()).save(any());
    }

    /**
     * A pod is expected to start with PostgreSQL unavailable and pick its work up when the database
     * returns (DIST-08). So this runs off the boot thread and retries; it must never be what stops
     * a context from coming up.
     */
    @Test
    void aDatabaseThatIsNotThereYetDoesNotFailTheBoot() throws Exception {
        when(stepExecutionRepository.findPendingOrRunning())
                .thenThrow(new IllegalStateException("connection refused"));

        var runner = runner();
        runner.run(null);

        // run() returned rather than propagating, and the retry happens on a daemon thread that
        // cannot hold the JVM open either.
        var thread = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "step-rearm".equals(t.getName()))
                .findFirst();
        assertThat(thread).isPresent();
        assertThat(thread.get().isDaemon()).isTrue();
    }

    @Test
    void everyProcessWithStaleStepsIsArmedNotJustTheFirst() {
        var p1 = process("p-1");
        var p2 = process("p-2");
        var s1 = unarmed("se-1", "p-1", 60_000);
        var s2 = unarmed("se-2", "p-2", 60_000);

        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(s1, s2));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(p1));
        when(processRepository.findById("p-2")).thenReturn(Optional.of(p2));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(s1));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-2")).thenReturn(List.of(s2));
        lockGrants();

        runner().rearmOnce();

        verify(processLockService).runExclusively(org.mockito.ArgumentMatchers.eq("p-1"), any());
        verify(processLockService).runExclusively(org.mockito.ArgumentMatchers.eq("p-2"), any());
        verify(stepExecutionRepository, org.mockito.Mockito.times(2)).save(any());
    }
}
