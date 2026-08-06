package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
public class InMemoryCronStartScheduler {

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final IngressRouter ingressRouter;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-scan-interval-ms:10000}")
    long scanIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-enabled:true}")
    boolean enabled;

    private final Map<String, LocalDateTime> lastFireTimes = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "workflow-cron-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void start() {
        if (!enabled) {
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
            CronStarts.fireDue(workflowDefinitionRepository, ingressRouter, lastFireTimes, LocalDateTime.now());
        } catch (Throwable e) {
            log.error("Error checking cron process starts", e);
        }
    }
}
