package io.mateu.workflow.application.httpworkers;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The durable queue of tasks served to workers over HTTP. One row per step execution currently owed
 * a worker; the row goes when the worker answers. Leases make it safe to hand a task out from any
 * node: a lease that expires makes the task available again (redelivery), so a worker that died
 * mid-task costs a delay, never the task.
 *
 * <p>Every time is passed in by the caller, never read from the store's clock, so all the
 * comparisons use one clock whatever the database's time zone.
 */
public interface HttpTaskQueue {

    /**
     * Queues a dispatched task. A newer dispatch of the same step execution (a higher attempt: the
     * step was retried or the process restarted) replaces the row and its lease; the same or an older
     * one is a duplicate delivery and changes nothing.
     *
     * @return whether the task was stored
     */
    boolean offer(HttpTask task);

    /**
     * Leases up to {@code max} available tasks of the given topics, oldest first, to {@code workerId}
     * until {@code leaseUntil}. Available: not cancelled, and not leased or its lease expired before
     * {@code now}. Two concurrent claims — on any number of nodes — never get the same task.
     */
    List<HttpTask> claim(Collection<String> topics, int max, String workerId, LocalDateTime now,
                         LocalDateTime leaseUntil);

    Optional<HttpTask> find(String id);

    /**
     * Moves the lease's expiry, if {@code leaseToken} is still the task's lease and it is not
     * cancelled.
     *
     * @return false when the lease is no longer that one (the task was re-leased), it was cancelled,
     *         or the task is gone
     */
    boolean extend(String id, String leaseToken, LocalDateTime until);

    /** Gives a lease back at once (the activation that took it had nobody left to answer). */
    void release(String id, String leaseToken);

    /**
     * Removes the task, if it is still that dispatch.
     *
     * @param attempt the dispatch the caller answered; null removes whatever is there
     * @return whether a row was removed
     */
    boolean remove(String id, Integer attempt);

    /**
     * The engine cancelled the task: a task nobody holds is removed, a held one is flagged so the
     * worker learns it on its next heartbeat.
     */
    CancelOutcome cancel(String id, LocalDateTime now);

    /**
     * Removes cancelled tasks whose lease has expired (their worker never came back to hear it) and
     * tasks queued before {@code orphansBefore}.
     *
     * @return how many rows were removed
     */
    int purge(LocalDateTime now, LocalDateTime orphansBefore);

    /** How many tasks are available and how many leased, at {@code now}. */
    Counts counts(LocalDateTime now);

    enum CancelOutcome {
        /** The queue did not hold the task. */
        ABSENT,
        /** Nobody held it: removed. */
        REMOVED,
        /** A worker holds it: flagged for its next heartbeat. */
        FLAGGED
    }

    record Counts(long available, long leased) {
    }
}
