package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.RetryDueCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The memory-mode schedulers: no lock (one JVM), the same per-occurrence and per-process semantics
 * as the JPA ones, a failing scan does not cancel the schedule, and cron can be switched off.
 */
class InMemorySchedulersTest {

    private static Step step(StepType type) {
        return new Step("s1", "wd-1", type, "Step", null, null, null, null, false, null, null, null, null, null,
                0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    @Test
    void theTimeoutSchedulerPublishesTheCheckEachDueStepNeedsAndSurvivesAFailingScan() {
        var steps = mock(StepExecutionRepository.class);
        var publisher = mock(UpstreamEventPublisher.class);
        var published = new CopyOnWriteArrayList<DomainEvent>();
        doAnswer(inv -> published.add(inv.getArgument(0))).when(publisher).publish(any());
        when(steps.findDue(any()))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn(List.of(
                        StepExecution.create(step(StepType.ACTION), "p-timeout", 0).withStatus(StepExecutionStatus.RUNNING),
                        StepExecution.create(step(StepType.TIMER), "p-timer", 0).withStatus(StepExecutionStatus.RUNNING),
                        StepExecution.create(step(StepType.ACTION), "p-retry", 0).withStatus(StepExecutionStatus.AWAITING_RETRY)))
                .thenReturn(List.of());
        var scheduler = new InMemoryTimeoutScheduler(steps, publisher);
        scheduler.scanIntervalMs = 20;

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> published.size() >= 3);
        } finally {
            scheduler.stop();
        }

        assertThat(published).containsExactlyInAnyOrder(new TimeoutCheckRequested("p-timeout"),
                new TimerCheckRequested("p-timer"), new RetryDueCheckRequested("p-retry"));
    }

    @Test
    void theCronSchedulerFiresEachOccurrenceOnceAndKeepsGoingAfterAFailingScan() throws Exception {
        var definitions = mock(WorkflowDefinitionRepository.class);
        var calls = new AtomicInteger();
        when(definitions.findAll()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("blip");
            }
            return List.of(new WorkflowDefinition("tick", "Tick", 1, null, false, 0, false, null, 0, List.of()));
        });
        var fileSchedules = mock(CronScheduleRepository.class);
        when(fileSchedules.findAll()).thenReturn(List.of(new CronFileSchedule("repo", 0, "tick",
                new CronSchedule("every-second", "* * * * * *", "UTC", null))));
        var pauses = mock(SchedulePauseRepository.class);
        when(pauses.findAll()).thenReturn(Map.of());
        var router = mock(IngressRouter.class);
        var started = new CopyOnWriteArrayList<ProcessCreationRequested>();
        doAnswer(inv -> started.add(inv.getArgument(0))).when(router).route(any());
        var scheduler = new InMemoryCronStartScheduler(definitions, fileSchedules, pauses, router);
        scheduler.scanIntervalMs = 20;
        scheduler.enabled = true;

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(4)).until(() -> started.size() >= 2);
            Thread.sleep(300);
        } finally {
            scheduler.stop();
        }

        assertThat(started).extracting(ProcessCreationRequested::businessKey).doesNotHaveDuplicates();
    }

    @Test
    void aDisabledCronSchedulerNeverScans() throws Exception {
        var definitions = mock(WorkflowDefinitionRepository.class);
        var router = mock(IngressRouter.class);
        var scheduler = new InMemoryCronStartScheduler(definitions, mock(CronScheduleRepository.class),
                mock(SchedulePauseRepository.class), router);
        scheduler.scanIntervalMs = 20;
        scheduler.enabled = false;

        scheduler.start();
        Thread.sleep(200);
        scheduler.stop();

        verifyNoInteractions(definitions, router);
    }
}
