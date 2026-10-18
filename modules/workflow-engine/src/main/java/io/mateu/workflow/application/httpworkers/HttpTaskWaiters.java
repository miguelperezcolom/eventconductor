package io.mateu.workflow.application.httpworkers;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * The long-poll: activations that found nothing wait here, and one dispatcher thread hands them
 * tasks as they appear. Nothing waits on a servlet thread — an activation is a future the HTTP layer
 * completes asynchronously.
 *
 * <ul>
 *   <li><b>Fair across workers.</b> Waiters are served first-come first-served: the worker that has
 *       waited longest is offered the next task. Tasks are handed out oldest first across all the
 *       topics a worker asked for, so a quiet topic is never starved by a busy one.</li>
 *   <li><b>No busy loop.</b> The dispatcher sleeps until a task is queued on this node (the
 *       {@link HttpTaskSignal}), a waiter arrives, the earliest deadline passes, or
 *       {@code poll-interval} elapses — the last one for tasks queued by another node and leases
 *       that expired. A topic set that came back empty is not asked again in the same pass, so N
 *       idle workers on one topic cost one query per interval, not N.</li>
 *   <li><b>Bounded.</b> At most {@code max-waiting} activations wait on a node; past that, new ones
 *       are refused (429) instead of piling up.</li>
 * </ul>
 *
 * <p>The same thread runs the queue's maintenance (purging cancelled and orphaned rows, refreshing
 * the gauges) every {@code maintenance-interval}.
 */
@Slf4j
public class HttpTaskWaiters {

    private final HttpTaskService service;
    private final HttpTaskQueue queue;
    private final HttpTaskSignal signal;
    private final HttpWorkerMetrics metrics;
    private final Settings settings;

    private final ConcurrentLinkedDeque<Waiter> waiters = new ConcurrentLinkedDeque<>();
    private final Semaphore admission;
    private volatile boolean running;
    private Thread dispatcher;
    private long nextMaintenanceNanos;

    /** The knobs, from {@code workflow.workers.http.*}. */
    public record Settings(int maxWaiting, Duration pollInterval, Duration maintenanceInterval,
                           Duration orphanRetention) {
    }

    public HttpTaskWaiters(HttpTaskService service, HttpTaskQueue queue, HttpTaskSignal signal,
                           HttpWorkerMetrics metrics, Settings settings) {
        this.service = service;
        this.queue = queue;
        this.signal = signal;
        this.metrics = metrics;
        this.settings = settings;
        this.admission = new Semaphore(Math.max(1, settings.maxWaiting()));
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        metrics.waitingGauge(this::waitingCount);
        nextMaintenanceNanos = System.nanoTime();
        dispatcher = new Thread(this::loop, "http-workers-dispatch");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    public synchronized void stop() {
        running = false;
        signal.raise();
        if (dispatcher != null) {
            dispatcher.interrupt();
            try {
                dispatcher.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Waiter waiter;
        while ((waiter = waiters.poll()) != null) {
            waiter.future.complete(List.of());
        }
    }

    public int waitingCount() {
        return waiters.size();
    }

    /**
     * Asks for tasks: the future completes with the tasks leased to the worker as soon as there are
     * some, or with an empty list once {@code wait} has passed.
     *
     * @throws HttpTaskException {@code OVERLOADED} when {@code max-waiting} activations already wait here
     */
    public CompletableFuture<List<HttpTask>> activate(String workerId, Set<String> topics, int maxTasks,
                                                      Duration lease, Duration wait) {
        if (!running) {
            throw new HttpTaskException(HttpTaskException.Reason.UNAVAILABLE, "The worker API is shutting down.");
        }
        if (!admission.tryAcquire()) {
            metrics.rejected("overloaded");
            throw new HttpTaskException(HttpTaskException.Reason.OVERLOADED,
                    "Too many activations are waiting on this node; retry shortly.");
        }
        var future = new CompletableFuture<List<HttpTask>>();
        future.whenComplete((tasks, failure) -> admission.release());
        var waiter = new Waiter(workerId, Set.copyOf(new LinkedHashSet<>(topics)), maxTasks, lease,
                System.nanoTime() + Math.max(0, wait.toNanos()), future);
        waiters.add(waiter);
        signal.raise();
        return future;
    }

    private void loop() {
        while (running) {
            try {
                signal.await(sleepMillis());
                if (!running) {
                    return;
                }
                pass();
                maintainIfDue();
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            } catch (Throwable e) {
                log.error("HTTP worker dispatch pass failed", e);
                pause();
            }
        }
    }

    /** Until the next reason to look: the poll interval, or the earliest deadline if sooner. */
    private long sleepMillis() {
        var now = System.nanoTime();
        var sleep = settings.pollInterval().toNanos();
        for (var waiter : waiters) {
            sleep = Math.min(sleep, waiter.deadlineNanos - now);
        }
        sleep = Math.min(sleep, nextMaintenanceNanos - now);
        return Math.max(1, sleep / 1_000_000 + (sleep % 1_000_000 > 0 ? 1 : 0));
    }

    /** One look at the queue for every waiter, oldest waiter first. */
    void pass() {
        Set<Set<String>> exhausted = new HashSet<>();
        for (var iterator = waiters.iterator(); iterator.hasNext(); ) {
            var waiter = iterator.next();
            if (waiter.future.isDone()) {
                iterator.remove();
                continue;
            }
            List<HttpTask> tasks = List.of();
            if (!exhausted.contains(waiter.topics)) {
                try {
                    tasks = service.claim(waiter.workerId, waiter.topics, waiter.maxTasks, waiter.lease);
                } catch (RuntimeException e) {
                    log.warn("Could not claim tasks for worker {}: {}", waiter.workerId, e.toString());
                    exhausted.add(waiter.topics);
                }
                if (tasks.size() < waiter.maxTasks) {
                    exhausted.add(waiter.topics);
                }
            }
            if (!tasks.isEmpty()) {
                iterator.remove();
                if (!waiter.future.complete(tasks)) {
                    // The caller is gone (its request timed out): nobody will work these.
                    service.release(tasks);
                    signal.raise();
                }
            } else if (System.nanoTime() - waiter.deadlineNanos >= 0) {
                iterator.remove();
                waiter.future.complete(List.of());
            }
        }
    }

    private void maintainIfDue() {
        if (System.nanoTime() - nextMaintenanceNanos < 0) {
            return;
        }
        nextMaintenanceNanos = System.nanoTime() + settings.maintenanceInterval().toNanos();
        maintain();
    }

    /** Purges cancelled and orphaned rows and refreshes the queue gauges. */
    void maintain() {
        try {
            var now = service.now();
            var purged = queue.purge(now, now.minus(settings.orphanRetention()));
            if (purged > 0) {
                log.info("Purged {} cancelled or orphaned HTTP worker task(s)", purged);
            }
            var counts = queue.counts(now);
            metrics.queueCounts(counts.available(), counts.leased());
        } catch (RuntimeException e) {
            log.warn("HTTP worker queue maintenance failed: {}", e.toString());
        }
    }

    private void pause() {
        try {
            Thread.sleep(Math.max(10, settings.pollInterval().toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: the waiters' worker ids, in serving order. */
    List<String> waitingWorkers() {
        var ids = new ArrayList<String>();
        waiters.forEach(waiter -> ids.add(waiter.workerId));
        return ids;
    }

    private record Waiter(String workerId, Set<String> topics, int maxTasks, Duration lease, long deadlineNanos,
                          CompletableFuture<List<HttpTask>> future) {
    }
}
