package io.mateu.workflow.application.sync;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ReplySignal;
import io.mateu.workflow.domain.aggregates.Process;
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
 * The synchronous callers waiting on this pod, and the three ways they are woken.
 *
 * <p>The reply is always a committed row on the process; the only question is how soon the pod
 * holding the connection finds out. Layered like {@code OutboxSignal}:
 * <ol>
 *   <li><b>Local signal</b> — the pod that recorded the reply says so on commit
 *       ({@link #replyRecorded}). With the fast path that is the common case, and in memory mode it
 *       is the whole story.</li>
 *   <li><b>Cross-pod notification</b> — a reply recorded on another pod of the same database
 *       ({@link #wake}, called by the PostgreSQL listener when there is one).</li>
 *   <li><b>Poll</b> — one query per pod per interval over <em>every</em> waiting process at once
 *       ({@code workflow.sync.poll-interval-ms}): the fallback for other databases, for a missed
 *       notification, and the bound on how late any caller can hear.</li>
 * </ol>
 *
 * <p>Nothing about the process depends on anybody waiting: a waiter is a future and a timer, and
 * losing it — the pod dies, the caller hangs up — loses nothing but the connection.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncReplyWaiters implements ReplySignal {

    final ProcessRepository processRepository;

    /** Optional (the waiters run without it in plain unit tests): wake-ups are counted, by how. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    io.mateu.workflow.application.out.WorkflowMetrics workflowMetrics;

    @org.springframework.beans.factory.annotation.Value("${workflow.sync.poll-interval-ms:250}")
    long pollIntervalMs;

    private final Map<String, List<CompletableFuture<Optional<Process>>>> waiting = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "sync-reply-waiters");
            thread.setDaemon(true);
            return thread;
        });
        if (pollIntervalMs > 0) {
            scheduler.scheduleWithFixedDelay(this::pollQuietly, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        waiting.values().forEach(futures -> futures.forEach(future -> future.complete(Optional.empty())));
        waiting.clear();
    }

    /**
     * Waits up to {@code wait} for the process to reply. Completes with the replied process, or with
     * empty when the wait runs out first — the caller then answers 202 and the process carries on.
     */
    public CompletableFuture<Optional<Process>> await(String processId, Duration wait) {
        var future = new CompletableFuture<Optional<Process>>();
        waiting.computeIfAbsent(processId, id -> new CopyOnWriteArrayList<>()).add(future);
        future.whenComplete((result, error) -> forget(processId, future));
        // Registered first, then checked: a reply that landed before the registration is found here,
        // one that lands after it is signalled — there is no gap between the two for it to fall in.
        check(processId);
        if (!future.isDone()) {
            var millis = Math.max(0, wait.toMillis());
            scheduler.schedule(() -> future.complete(Optional.empty()), millis, TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /** How many callers are waiting on this pod right now. */
    public int waitingCount() {
        return waiting.values().stream().mapToInt(List::size).sum();
    }

    /** Whether anyone on this pod is waiting for this process. */
    public boolean isWaitingFor(String processId) {
        return waiting.containsKey(processId);
    }

    @Override
    public void replyRecorded(String processId) {
        if (!waiting.containsKey(processId)) {
            return; // nobody here is waiting for it: nothing to do, and nothing to read
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake(processId);
                }
            });
        } else {
            wake(processId);
        }
    }

    /** Re-reads the process off-thread and answers its waiters if it has replied (a local signal). */
    public void wake(String processId) {
        wake(processId, "local");
    }

    /** The same, for a reply another node announced (PostgreSQL NOTIFY). */
    public void wakeFromNotification(String processId) {
        wake(processId, "notify");
    }

    private void wake(String processId, String via) {
        if (waiting.containsKey(processId) && scheduler != null) {
            scheduler.execute(() -> check(processId, via));
        }
    }

    private void check(String processId) {
        check(processId, "registration");
    }

    private void check(String processId, String via) {
        try {
            var process = processRepository.findById(processId).filter(Process::hasReplied);
            if (process.isPresent()) {
                var futures = waiting.get(processId);
                if (futures != null) {
                    var answered = futures.stream().filter(future -> future.complete(process)).count();
                    if (answered > 0 && workflowMetrics != null) {
                        workflowMetrics.syncWakeup(via);
                    }
                }
            }
        } catch (Exception e) {
            // A read that fails now is retried by the next poll; the waiter's own timer still runs.
            log.warn("Could not check the reply of process {}: {}", processId, e.getMessage());
        }
    }

    private void pollQuietly() {
        try {
            if (waiting.isEmpty()) {
                return;
            }
            processRepository.findRepliedAmong(List.copyOf(waiting.keySet())).forEach(id -> check(id, "poll"));
        } catch (Exception e) {
            log.warn("Polling for synchronous replies failed, will try again: {}", e.getMessage());
        }
    }

    private void forget(String processId, CompletableFuture<Optional<Process>> future) {
        waiting.computeIfPresent(processId, (id, futures) -> {
            futures.remove(future);
            return futures.isEmpty() ? null : futures;
        });
    }
}
