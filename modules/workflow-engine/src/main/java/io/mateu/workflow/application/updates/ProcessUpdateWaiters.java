package io.mateu.workflow.application.updates;

import io.mateu.workflow.application.out.ProcessUpdateRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The callers waiting on this pod for an update's outcome, woken the way synchronous invocations are:
 * on commit by the pod that finished the update ({@link #updateFinished}), and by a poll — one query per
 * interval over every waiting update at once — when another pod finished it. Losing a waiter loses
 * nothing but the connection: the outcome is a committed row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessUpdateWaiters {

    final ProcessUpdateRepository repository;

    @org.springframework.beans.factory.annotation.Value("${workflow.sync.poll-interval-ms:250}")
    long pollIntervalMs = 250;

    private final Map<String, List<CompletableFuture<Optional<ProcessUpdate>>>> waiting = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newScheduledThreadPool(1, runnable -> {
            var thread = new Thread(runnable, "process-update-waiters");
            thread.setDaemon(true);
            return thread;
        });
        if (pollIntervalMs > 0) {
            scheduler.scheduleWithFixedDelay(this::pollQuietly, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        waiting.values().forEach(futures -> futures.forEach(future -> future.complete(Optional.empty())));
        waiting.clear();
    }

    /**
     * Waits up to {@code wait} for the update to reach its outcome. Completes with it, or with empty when
     * the wait runs out first — the caller then answers 202 and the update carries on.
     */
    public CompletableFuture<Optional<ProcessUpdate>> await(String updateId, Duration wait) {
        var future = new CompletableFuture<Optional<ProcessUpdate>>();
        waiting.computeIfAbsent(updateId, id -> new CopyOnWriteArrayList<>()).add(future);
        future.whenComplete((result, error) -> forget(updateId, future));
        // Registered first, then checked: an outcome recorded before the registration is found here, one
        // recorded after it is signalled — no gap between the two.
        check(updateId);
        if (!future.isDone()) {
            scheduler.schedule(() -> future.complete(Optional.empty()), Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /** How many callers are waiting on this pod. */
    public int waitingCount() {
        return waiting.values().stream().mapToInt(List::size).sum();
    }

    /** The update reached its outcome here: wake its waiters once that is committed. */
    public void updateFinished(String updateId) {
        if (!waiting.containsKey(updateId)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake(updateId);
                }
            });
        } else {
            wake(updateId);
        }
    }

    private void wake(String updateId) {
        if (waiting.containsKey(updateId) && scheduler != null) {
            scheduler.execute(() -> check(updateId));
        }
    }

    private void check(String updateId) {
        try {
            var update = repository.findById(updateId).filter(ProcessUpdate::isFinal);
            if (update.isPresent()) {
                var futures = waiting.get(updateId);
                if (futures != null) {
                    futures.forEach(future -> future.complete(update));
                }
            }
        } catch (Exception e) {
            log.warn("Could not check update {}: {}", updateId, e.getMessage());
        }
    }

    private void pollQuietly() {
        try {
            if (waiting.isEmpty()) {
                return;
            }
            repository.findFinishedAmong(List.copyOf(waiting.keySet())).forEach(update -> {
                var futures = waiting.get(update.id());
                if (futures != null) {
                    futures.forEach(future -> future.complete(Optional.of(update)));
                }
            });
        } catch (Exception e) {
            log.warn("Polling for process updates failed, will try again: {}", e.getMessage());
        }
    }

    private void forget(String updateId, CompletableFuture<Optional<ProcessUpdate>> future) {
        waiting.computeIfPresent(updateId, (id, futures) -> {
            futures.remove(future);
            return futures.isEmpty() ? null : futures;
        });
    }
}
