package io.mateu.workflow.application.ratelimit;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.infra.config.WorkerRateLimitProperties;
import io.mateu.workflow.time.EngineClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Worker rate limits per topic ({@code workflow.workers.rate-limits.<topic>}, FLOW-CONTROL-PLAN.md §3): asked by
 * the dispatch before a task leaves for its worker, it either takes a permit or defers the task; the
 * {@code RateLimitedDispatchPass} then dispatches deferred tasks as permits free up — higher priority first,
 * and within a priority one task per process in turn, so a process with a thousand items cannot starve the rest.
 */
@Service
@Slf4j
public class DispatchRateLimiter {

    private final WorkerRateLimitProperties properties;
    private final RateLimitStore store;
    private final WorkflowMetrics metrics;

    public DispatchRateLimiter(WorkerRateLimitProperties properties, RateLimitStore store, WorkflowMetrics metrics) {
        this.properties = properties;
        this.store = store;
        this.metrics = metrics;
    }

    /** Whether {@code topic} has a rate limit at all. */
    public boolean limits(String topic) {
        return properties.limitOf(topic).isPresent();
    }

    /**
     * Takes a permit for a task about to be dispatched on {@code topic}, or defers it. A task never jumps the
     * topic's waiting tasks: while any wait, a new one waits behind them.
     *
     * @return true when it may be dispatched now
     */
    public boolean admitOrDefer(String stepExecutionId, String processId, String topic, int priority) {
        var limit = properties.limitOf(topic).orElse(null);
        if (limit == null) {
            return true;
        }
        var now = EngineClock.now();
        if (!store.hasDeferred(topic) && store.tryAcquire(topic, limit.getPermits(), limit.getPeriod(), now)) {
            return true;
        }
        store.defer(new RateLimitStore.Deferred(stepExecutionId, processId, topic, priority, now));
        metrics.rateLimited(topic, "deferred");
        log.debug("Task {} of process {} waits for a permit of topic '{}' ({} per {})", stepExecutionId, processId,
                topic, limit.getPermits(), limit.getPeriod());
        return false;
    }

    /**
     * Dispatches deferred tasks while permits last, through {@code dispatch}.
     *
     * @return how many were dispatched
     */
    public int releaseDue(Consumer<String> dispatch) {
        int released = 0;
        for (var topic : store.deferredTopics()) {
            var limit = properties.limitOf(topic).orElse(null);
            var candidates = fairOrder(store.deferred(topic, Math.max(50, limit == null ? 500 : limit.getPermits() * 4)));
            for (var task : candidates) {
                if (limit != null && !store.tryAcquire(topic, limit.getPermits(), limit.getPeriod(), EngineClock.now())) {
                    break;
                }
                if (!store.claim(task.stepExecutionId())) {
                    continue;   // another pod dispatched it; the permit is lost, which only slows this window
                }
                try {
                    dispatch.accept(task.stepExecutionId());
                } catch (RuntimeException e) {
                    // Not dispatched: back in line (its place by deferredAt), for the next pass.
                    store.defer(task);
                    throw e;
                }
                metrics.rateLimited(topic, "released");
                released++;
            }
        }
        return released;
    }

    /** When the next window of a topic with waiting tasks opens. */
    public Optional<LocalDateTime> nextDueAt() {
        LocalDateTime next = null;
        for (var topic : store.deferredTopics()) {
            var limit = properties.limitOf(topic).orElse(null);
            var end = limit == null ? Optional.of(EngineClock.now()) : store.windowEnd(topic, limit.getPeriod());
            if (end.isPresent() && (next == null || end.get().isBefore(next))) {
                next = end.get();
            }
        }
        return Optional.ofNullable(next);
    }

    /**
     * Priority first; within a priority, round-robin across processes (each process's oldest first), so
     * every process waiting at that priority gets a task out before any gets a second one.
     */
    static List<RateLimitStore.Deferred> fairOrder(List<RateLimitStore.Deferred> tasks) {
        var byPriority = new java.util.TreeMap<Integer, LinkedHashMap<String, java.util.ArrayDeque<RateLimitStore.Deferred>>>(
                java.util.Comparator.reverseOrder());
        tasks.stream()
                .sorted(java.util.Comparator.comparing(RateLimitStore.Deferred::deferredAt)
                        .thenComparing(RateLimitStore.Deferred::stepExecutionId))
                .forEach(task -> byPriority.computeIfAbsent(task.priority(), p -> new LinkedHashMap<>())
                        .computeIfAbsent(task.processId(), p -> new java.util.ArrayDeque<>()).add(task));
        var ordered = new ArrayList<RateLimitStore.Deferred>(tasks.size());
        for (var processes : byPriority.values()) {
            boolean any = true;
            while (any) {
                any = false;
                for (var queue : processes.values()) {
                    var next = queue.poll();
                    if (next != null) {
                        ordered.add(next);
                        any = true;
                    }
                }
            }
        }
        return ordered;
    }
}
