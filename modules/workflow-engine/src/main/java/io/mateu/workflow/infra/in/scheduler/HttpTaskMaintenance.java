package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskSignal;
import io.mateu.workflow.application.httpworkers.HttpWorkerMetrics;
import io.mateu.workflow.time.EngineClock;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP worker queue's periodic pass: purges cancelled tasks whose lease expired (their worker
 * never came back to hear it) and orphans older than {@code orphan-retention}, refreshes the
 * {@code eventconductor.workers.http.queued} gauges, and wakes the long-poll dispatcher so tasks whose
 * lease expired are offered to waiting workers at once rather than at their next poll.
 *
 * <p>Expiry itself needs no pass — an expired lease simply matches the next claim — so a missed or
 * late pass delays a purge, never a redelivery.
 *
 * <p>Time: the orphan cut-off is a retention cut-off, business time, so it is read from
 * {@link EngineClock} (as {@code created_at} is written); lease expiry is compared on the lease clock
 * ({@link HttpTaskService#now()}), wall time — see {@code HttpTaskService}. Every node may run it: the
 * deletes are idempotent.
 */
@Slf4j
public class HttpTaskMaintenance implements SchedulerPass {

    private final HttpTaskQueue queue;
    private final HttpTaskService service;
    private final HttpTaskSignal signal;
    private final HttpWorkerMetrics metrics;
    private final Duration interval;
    private final Duration orphanRetention;
    private final boolean background;
    private ScheduledExecutorService scheduler;

    public HttpTaskMaintenance(HttpTaskQueue queue, HttpTaskService service, HttpTaskSignal signal,
                               HttpWorkerMetrics metrics, Duration interval, Duration orphanRetention,
                               boolean background) {
        this.queue = queue;
        this.service = service;
        this.signal = signal;
        this.metrics = metrics;
        this.interval = interval;
        this.orphanRetention = orphanRetention;
        this.background = background;
    }

    public void start() {
        if (!background) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "http-workers-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        var millis = Math.max(10, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::runQuietly, 0, millis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One pass: purge, refresh the gauges, wake the waiters. */
    @Override
    public int runOnce() {
        var purged = queue.purge(service.now(), EngineClock.now().minus(orphanRetention));
        if (purged > 0) {
            log.info("Purged {} cancelled or orphaned HTTP worker task(s)", purged);
        }
        var counts = queue.counts(service.now());
        metrics.queueCounts(counts.available(), counts.leased());
        if (counts.available() > 0) {
            signal.raise();
        }
        return purged;
    }

    void runQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("HTTP worker queue maintenance failed, will try again: {}", e.toString());
        }
    }
}
