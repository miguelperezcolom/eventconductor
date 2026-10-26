package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes {@code eventconductor.incidents.open}: how many processes are stopped on an incident (status
 * ERROR) right now, per definition. The failure counters say a process failed; this says how many are
 * still waiting for someone — what a dashboard of an integration graphs and what an alert watches.
 *
 * <p>One grouped count per pass, every {@code workflow.incidents.gauge-interval-ms} (a minute by default;
 * 0 turns it off). No lock: every pod counts the same shared rows and reports the same figure, so alert
 * on the maximum across replicas, never the sum. A failed pass is logged and the gauge keeps its last
 * value until the next one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenIncidentsWatch implements SchedulerPass {

    final ProcessRepository processRepository;
    final WorkflowMetrics workflowMetrics;

    @org.springframework.beans.factory.annotation.Value("${workflow.incidents.gauge-interval-ms:60000}")
    long intervalMs = 60000;

    @org.springframework.beans.factory.annotation.Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    private ScheduledExecutorService watch;

    @PostConstruct
    void start() {
        if (intervalMs <= 0 || !background) {
            return;
        }
        watch = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "workflow-open-incidents-watch");
            thread.setDaemon(true);
            return thread;
        });
        watch.scheduleWithFixedDelay(this::reportQuietly, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (watch != null) {
            watch.shutdownNow();
        }
    }

    /**
     * One pass: counts the processes in ERROR per definition and publishes the gauge. Returns 0 — it
     * reports, it acts on nothing — so a caller looping until idle is not kept going by a backlog.
     */
    @Override
    public int runOnce() {
        Map<String, Long> open = processRepository.countByDefinitionWithStatus(ProcessStatus.ERROR);
        workflowMetrics.openIncidentsObserved(open);
        return 0;
    }

    void reportQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Could not count the open incidents; will retry", e);
        }
    }
}
