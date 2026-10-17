package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.SchedulePause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The JPA cron scheduler's loop around {@link CronStarts}: it scans only while it holds the leader
 * lock, releases that lock after every scan (also a failing one), fires each occurrence of a
 * schedule exactly once however many scans see it, skips a paused schedule, and does nothing at
 * all when cron is disabled.
 */
class CronStartSchedulerTest {

    private static final long CRON_LOCK_ID = 222333444L;

    private final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    private final CronScheduleRepository fileSchedules = mock(CronScheduleRepository.class);
    private final SchedulePauseRepository pauses = mock(SchedulePauseRepository.class);
    private final IngressRouter ingressRouter = mock(IngressRouter.class);
    private final FakeLeaderLock lock = new FakeLeaderLock();
    private final List<ProcessCreationRequested> started = new CopyOnWriteArrayList<>();

    private CronStartScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(definitions.findAll()).thenReturn(List.of(
                new WorkflowDefinition("tick", "Tick", 1, null, false, 0, false, null, 0, List.of())));
        // Every second, so a couple of occurrences fall inside a test.
        when(fileSchedules.findAll()).thenReturn(List.of(
                new CronFileSchedule("repo", 0, "tick", new CronSchedule("every-second", "* * * * * *", "UTC", null))));
        when(pauses.findAll()).thenReturn(Map.of());
        doAnswer(inv -> started.add(inv.getArgument(0))).when(ingressRouter).route(any());
        scheduler = new CronStartScheduler(definitions, fileSchedules, pauses, ingressRouter,
                lock.jdbcTemplate, lock);
        scheduler.scanIntervalMs = 20;
        scheduler.enabled = true;
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    private void runFor(Duration duration) throws InterruptedException {
        scheduler.start();
        Thread.sleep(duration.toMillis());
        scheduler.stop();
        Thread.sleep(50);
    }

    @Test
    void theLeaderFiresEachOccurrenceExactlyOnceAcrossManyScans() throws Exception {
        var from = Instant.now();
        runFor(Duration.ofMillis(2_300));
        var to = Instant.now();

        // ~100 scans saw 2 or 3 occurrences of an every-second schedule; each started one process.
        var keys = started.stream().map(ProcessCreationRequested::businessKey).toList();
        assertThat(keys).doesNotHaveDuplicates();
        long secondsElapsed = Duration.between(from, to).toSeconds();
        assertThat(keys).hasSizeBetween(1, (int) secondsElapsed + 1);
        assertThat(started).allSatisfy(s -> assertThat(s.workflowDefinitionId()).isEqualTo("tick"));
        // Every scan released the lock it took.
        assertThat(lock.locked).isNotEmpty().containsOnly(CRON_LOCK_ID);
        assertThat(lock.unlocked).hasSameSizeAs(lock.locked);
    }

    @Test
    void aPodThatIsNotTheLeaderNeitherScansNorReleasesALockItDoesNotHold() throws Exception {
        lock.leader = false;

        runFor(Duration.ofMillis(1_300));

        assertThat(lock.attempts.get()).isGreaterThan(1);
        assertThat(started).isEmpty();
        assertThat(lock.unlocked).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(definitions, ingressRouter);
    }

    @Test
    void aPausedScheduleDoesNotFire() throws Exception {
        when(pauses.findAll()).thenReturn(Map.of("every-second",
                new SchedulePause("every-second", Instant.now(), "ops")));

        runFor(Duration.ofMillis(1_300));

        assertThat(lock.attempts.get()).isGreaterThan(1);
        assertThat(started).isEmpty();
    }

    @Test
    void whenCronIsDisabledNothingRunsAtAll() throws Exception {
        scheduler.enabled = false;

        runFor(Duration.ofMillis(200));

        assertThat(lock.attempts.get()).isZero();
        org.mockito.Mockito.verifyNoInteractions(lock.jdbcTemplate, definitions, ingressRouter);
    }

    @Test
    void aFailingScanReleasesTheLockAndTheNextScanStillRuns() throws Exception {
        var calls = new AtomicInteger();
        when(definitions.findAll()).thenAnswer(inv -> {
            if (calls.incrementAndGet() <= 3) {
                throw new IllegalStateException("database blip");
            }
            return List.of(new WorkflowDefinition("tick", "Tick", 1, null, false, 0, false, null, 0, List.of()));
        });

        scheduler.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !started.isEmpty());
        scheduler.stop();

        assertThat(calls.get()).isGreaterThan(3);
        Thread.sleep(50);
        assertThat(lock.unlocked).hasSameSizeAs(lock.locked);
    }

    @Test
    void stoppingEndsTheLoop() throws Exception {
        scheduler.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> lock.attempts.get() > 0);
        scheduler.stop();
        Thread.sleep(100);
        var attemptsAfterStop = lock.attempts.get();

        Thread.sleep(200);

        assertThat(lock.attempts.get()).isEqualTo(attemptsAfterStop);
    }
}
