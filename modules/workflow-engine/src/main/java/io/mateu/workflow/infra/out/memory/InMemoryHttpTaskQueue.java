package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.httpworkers.HttpTask;
import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskService;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The HTTP worker queue in the heap, for memory mode: one JVM, so one monitor stands in for the
 * database's row locks. Lost on restart, like everything else in memory mode. Created by
 * {@code HttpWorkersConfiguration} when the transport is enabled.
 */
public class InMemoryHttpTaskQueue implements HttpTaskQueue {

    private final Map<String, HttpTask> tasks = new LinkedHashMap<>();

    @Override
    public synchronized boolean offer(HttpTask task) {
        var existing = tasks.get(task.id());
        if (existing != null && existing.attemptOrZero() >= task.attemptOrZero()) {
            return false;
        }
        tasks.put(task.id(), task);
        return true;
    }

    @Override
    public synchronized List<HttpTask> claim(Collection<String> topics, int max, String workerId,
                                             LocalDateTime now, LocalDateTime leaseUntil) {
        var wanted = Set.copyOf(topics);
        var claimed = tasks.values().stream()
                .filter(task -> wanted.contains(task.topic()))
                .filter(task -> !task.cancelled() && !task.leasedAt(now))
                .sorted(HttpTask.CLAIM_ORDER)
                .limit(Math.max(0, max))
                .map(task -> task.leased(workerId, HttpTaskService.newLeaseToken(), leaseUntil))
                .toList();
        claimed.forEach(task -> tasks.put(task.id(), task));
        return claimed;
    }

    @Override
    public synchronized Optional<HttpTask> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized boolean extend(String id, String leaseToken, LocalDateTime until) {
        var task = tasks.get(id);
        if (task == null || task.cancelled() || !Objects.equals(leaseToken, task.leaseToken())) {
            return false;
        }
        tasks.put(id, task.withLeaseUntil(until));
        return true;
    }

    @Override
    public synchronized void release(String id, String leaseToken) {
        var task = tasks.get(id);
        if (task != null && Objects.equals(leaseToken, task.leaseToken())) {
            tasks.put(id, task.withLeaseUntil(null));
        }
    }

    @Override
    public synchronized boolean remove(String id, Integer attempt) {
        var task = tasks.get(id);
        if (task == null || (attempt != null && task.attempt() != null && !attempt.equals(task.attempt()))) {
            return false;
        }
        tasks.remove(id);
        return true;
    }

    @Override
    public synchronized CancelOutcome cancel(String id, LocalDateTime now) {
        var task = tasks.get(id);
        if (task == null) {
            return CancelOutcome.ABSENT;
        }
        if (task.leasedAt(now)) {
            tasks.put(id, task.markedCancelled());
            return CancelOutcome.FLAGGED;
        }
        tasks.remove(id);
        return CancelOutcome.REMOVED;
    }

    @Override
    public synchronized int purge(LocalDateTime now, LocalDateTime orphansBefore) {
        var before = tasks.size();
        tasks.values().removeIf(task -> (task.cancelled() && !task.leasedAt(now))
                || task.createdAt().isBefore(orphansBefore));
        return before - tasks.size();
    }

    @Override
    public synchronized Counts counts(LocalDateTime now) {
        long leased = tasks.values().stream().filter(task -> task.leasedAt(now)).count();
        long available = tasks.values().stream().filter(task -> !task.cancelled() && !task.leasedAt(now)).count();
        return new Counts(available, leased);
    }
}
