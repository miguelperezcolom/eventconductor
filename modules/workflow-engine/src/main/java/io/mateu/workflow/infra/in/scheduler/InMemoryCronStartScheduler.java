package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cron scanner for memory persistence (the default mode): starts a process instance for
 * every occurrence of an ACTIVE definition's cron expression. A single JVM needs no leader
 * election, so a plain scheduled executor replaces the advisory-lock loop of
 * {@link CronStartScheduler}.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class InMemoryCronStartScheduler implements SchedulerPass {

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final io.mateu.workflow.application.out.CronScheduleRepository cronScheduleRepository;
    final io.mateu.workflow.application.out.SchedulePauseRepository schedulePauseRepository;
    final IngressRouter ingressRouter;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-scan-interval-ms:10000}")
    long scanIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-enabled:true}")
    boolean enabled;

    @org.springframework.beans.factory.annotation.Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    private final Map<String, ZonedDateTime> lastFireTimes = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "workflow-cron-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void start() {
        if (!enabled || !background) {
            return;
        }
        executor.scheduleWithFixedDelay(this::scan, scanIntervalMs, scanIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void scan() {
        try {
            runOnce();
        } catch (Throwable e) {
            log.error("Error checking cron process starts", e);
        }
    }

    /**
     * One cron scan: starts a process for every occurrence elapsed since the last scan. Does nothing
     * when cron starts are off ({@code workflow.cron-enabled=false}).
     */
    @Override
    public int runOnce() {
        if (!enabled) {
            return 0;
        }
        var started = new java.util.concurrent.atomic.AtomicInteger();
        CronStarts.fireDue(workflowDefinitionRepository, cronScheduleRepository, schedulePauseRepository,
                request -> {
                    started.incrementAndGet();
                    ingressRouter.route(request);
                },
                lastFireTimes, EngineClock.instant());
        return started.get();
    }

    /**
     * The earliest occurrence of any tracked schedule strictly after the last one this scheduler
     * fired, or empty when there is none — what a caller skipping time needs in order to stop the
     * clock at each occurrence instead of jumping past it.
     */
    public java.util.Optional<Instant> nextOccurrence() {
        if (!enabled) {
            return java.util.Optional.empty();
        }
        return CronStarts.nextOccurrence(workflowDefinitionRepository, cronScheduleRepository, schedulePauseRepository,
                lastFireTimes, EngineClock.instant());
    }
}
