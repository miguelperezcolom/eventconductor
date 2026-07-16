package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Timeout and timer scanner for memory persistence (the default mode). Without it, step
 * timeouts and TIMER steps would silently never fire outside JPA mode. A single JVM needs
 * no leader election, so a plain scheduled executor replaces the advisory-lock loop of
 * {@link TimeoutScheduler}.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class InMemoryTimeoutScheduler {

    final StepExecutionRepository stepExecutionRepository;
    final UpstreamEventPublisher upstreamEventPublisher;

    @org.springframework.beans.factory.annotation.Value("${workflow.timeout-scan-interval-ms:10000}")
    long scanIntervalMs;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "workflow-timeout-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(this::scan, scanIntervalMs, scanIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void scan() {
        try {
            var now = LocalDateTime.now();
            var deadlines = StepDeadlines.scan(stepExecutionRepository.findPendingOrRunning(), now);
            deadlines.timedOutProcessIds().forEach(processId ->
                    upstreamEventPublisher.publish(new TimeoutCheckRequested(processId)));
            deadlines.dueTimerProcessIds().forEach(processId ->
                    upstreamEventPublisher.publish(new TimerCheckRequested(processId)));
        } catch (Throwable e) {
            log.error("Error checking step timeouts and timers", e);
        }
    }
}
