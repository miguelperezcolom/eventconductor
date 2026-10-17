package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.RetryDueCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The JPA timeout scheduler: while it holds the leader lock each scan turns the due step executions
 * into the right check request — timeout, timer or retry — per process; a pod that is not the leader
 * publishes nothing; and the two stalled-work watches report on their own loops, leader or not.
 */
class TimeoutSchedulerTest {

    private static final long TIMEOUT_LOCK_ID = 777888999L;

    private final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    private final ProcessRepository processes = mock(ProcessRepository.class);
    private final UpstreamEventPublisher publisher = mock(UpstreamEventPublisher.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    private final FakeLeaderLock lock = new FakeLeaderLock();
    private final List<DomainEvent> published = new CopyOnWriteArrayList<>();

    private TimeoutScheduler scheduler;

    private static Step step(StepType type) {
        return new Step("s1", "wd-1", type, "Step", null, null, null, null, false, null, null, null, null, null,
                0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static StepExecution execution(String processId, StepType type, StepExecutionStatus status) {
        return StepExecution.create(step(type), processId, 0).withStatus(status);
    }

    @BeforeEach
    void setUp() {
        doAnswer(inv -> published.add(inv.getArgument(0))).when(publisher).publish(any());
        when(steps.findDue(any())).thenReturn(List.of(
                execution("p-timeout", StepType.ACTION, StepExecutionStatus.RUNNING),
                execution("p-timeout", StepType.ACTION, StepExecutionStatus.RUNNING),
                execution("p-timer", StepType.TIMER, StepExecutionStatus.RUNNING),
                execution("p-retry", StepType.ACTION, StepExecutionStatus.AWAITING_RETRY)));
        scheduler = new TimeoutScheduler(steps, processes, publisher, lock.jdbcTemplate, lock, metrics);
        scheduler.scanIntervalMs = 20;
        // The watches are slow by default; here only the deadline scan is under test unless a test says so.
        scheduler.stalledScanIntervalMs = 60_000;
        scheduler.stalledProcessScanIntervalMs = 60_000;
        scheduler.stalledAfterMs = 900_000;
        scheduler.stalledProcessAfterMs = 1_800_000;
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    void theLeaderTurnsEachDueStepIntoTheCheckItNeedsOncePerProcessPerScan() {
        // One scan's worth.
        when(steps.findDue(any())).thenReturn(List.of(
                execution("p-timeout", StepType.ACTION, StepExecutionStatus.RUNNING),
                execution("p-timeout", StepType.ACTION, StepExecutionStatus.RUNNING),
                execution("p-timer", StepType.TIMER, StepExecutionStatus.RUNNING),
                execution("p-retry", StepType.ACTION, StepExecutionStatus.AWAITING_RETRY)))
                .thenReturn(List.of());

        scheduler.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> lock.locked.size() >= 3);
        scheduler.stop();

        assertThat(published).containsExactlyInAnyOrder(
                new TimeoutCheckRequested("p-timeout"),
                new TimerCheckRequested("p-timer"),
                new RetryDueCheckRequested("p-retry"));
        assertThat(lock.locked).containsOnly(TIMEOUT_LOCK_ID);
    }

    @Test
    void everyScanReleasesTheLockEvenWhenItFails() throws Exception {
        when(steps.findDue(any())).thenThrow(new IllegalStateException("database blip"));

        scheduler.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> lock.locked.size() >= 3);
        scheduler.stop();
        Thread.sleep(50);

        assertThat(lock.unlocked).hasSameSizeAs(lock.locked);
        assertThat(published).isEmpty();
    }

    @Test
    void aPodThatIsNotTheLeaderPublishesNothing() throws Exception {
        lock.leader = false;

        scheduler.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> lock.attempts.get() >= 3);
        scheduler.stop();

        verify(steps, never()).findDue(any());
        assertThat(published).isEmpty();
        assertThat(lock.unlocked).isEmpty();
    }

    @Test
    void theStalledStepWatchReportsTheCountOnItsOwnLoopWithoutTheLock() {
        lock.leader = false;
        when(steps.countStalled(any())).thenReturn(4L);
        scheduler.stalledScanIntervalMs = 20;
        scheduler.stalledAfterMs = 60_000;
        var before = LocalDateTime.now();

        scheduler.start();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(metrics, atLeastOnce()).stalledStepsObserved(4L));

        // The threshold is "older than stalledAfterMs".
        var captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(steps, atLeastOnce()).countStalled(captor.capture());
        assertThat(captor.getValue()).isBefore(before.minusSeconds(59)).isAfter(before.minusSeconds(120));
    }

    @Test
    void theStalledProcessWatchReportsHowManyItFound() {
        lock.leader = false;
        when(processes.findStalled(any(), anyInt())).thenReturn(List.of("p-1", "p-2"));
        scheduler.stalledProcessScanIntervalMs = 20;

        scheduler.start();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(metrics, atLeastOnce()).stalledProcessesObserved(2L));
        verify(processes, atLeastOnce()).findStalled(any(), org.mockito.ArgumentMatchers.eq(20));
    }

    @Test
    void aFailingWatchKeepsWatching() {
        when(steps.countStalled(any())).thenThrow(new IllegalStateException("blip")).thenReturn(0L);
        when(processes.findStalled(any(), anyInt())).thenThrow(new IllegalStateException("blip")).thenReturn(List.of());
        scheduler.stalledScanIntervalMs = 20;
        scheduler.stalledProcessScanIntervalMs = 20;

        scheduler.start();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            verify(metrics, atLeastOnce()).stalledStepsObserved(0L);
            verify(metrics, atLeastOnce()).stalledProcessesObserved(0L);
        });
    }
}
