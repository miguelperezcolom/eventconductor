package io.mateu.workflow.application.out;

import java.util.List;
import java.util.Optional;

/**
 * A named, per-key mutual-exclusion lock with a FIFO wait queue, created on the fly.
 *
 * <p>A lock is identified by {@code (lockName, lockKey)}: {@code lockName} is the lock domain a
 * definition declares (e.g. {@code "booking"}), {@code lockKey} is the evaluated key (e.g. the
 * {@code bookingId}). The first caller to ask for a key that nobody holds gets it; the rest are
 * enqueued and admitted, one at a time, in the order they arrived. This is what serializes every
 * process (or critical section) that targets the same entity.
 *
 * <p>Two implementations back this port, chosen by {@code workflow.persistence}: an in-heap one for
 * {@code memory} mode and a JDBC one for {@code jpa} mode that coordinates across pods through the
 * database. The engine always drives acquire/release from inside the per-process lock
 * ({@code ProcessLockService}), so within one process these calls are already serial; the JDBC
 * implementation's row lock is what serializes them across processes and pods.
 *
 * <p>See {@code LOCK-SERIALIZATION-PLAN.md} at the repository root for the full design.
 */
public interface LockService {

    /** Whether {@link #acquire} took the lock or parked the caller in the queue. */
    enum Outcome {
        /** The caller now holds the lock (or already held it — acquisition is reentrant). */
        ACQUIRED,
        /** Another process holds the lock; the caller was appended to the FIFO wait queue. */
        ENQUEUED
    }

    /**
     * A lock handed to a waiter when the previous holder released it. The caller of
     * {@link #release}/{@link #releaseAll} is responsible for waking {@link #processId()} so its
     * step-over runs and the newly-granted holder can proceed.
     */
    record Grant(String lockName, String lockKey, String processId, String stepExecutionId) {}

    /**
     * Try to take {@code (lockName, lockKey)} for {@code processId}. {@code stepExecutionId} is the
     * waiting step for a step-level lock, or {@code null} for a process-level lock.
     *
     * <p>Reentrant: a process re-acquiring a lock it already holds gets {@link Outcome#ACQUIRED}
     * again without perturbing the queue. Enqueuing is idempotent: a process already waiting on the
     * key is not queued twice.
     */
    Outcome acquire(String lockName, String lockKey, String processId, String stepExecutionId);

    /**
     * Release {@code (lockName, lockKey)} if {@code processId} holds it. If a waiter was queued it
     * becomes the new holder and is returned so the caller can wake its process; otherwise the lock
     * is freed and the result is empty. A release by a process that does not hold the lock is a
     * no-op returning empty.
     */
    Optional<Grant> release(String lockName, String lockKey, String processId);

    /**
     * Release every lock {@code processId} holds and drop it from every queue it waits in — the
     * guaranteed cleanup when a process reaches a terminal state. Returns one {@link Grant} per lock
     * that had a waiter admitted, so the caller can wake each of them.
     */
    List<Grant> releaseAll(String processId);

    /**
     * Force-release every held lock whose lease has expired, admitting the next waiter for each and
     * returning the grants to wake. The crash backstop: a lock is normally released by its UNLOCK
     * step or when its process reaches a terminal state, but a pod that dies mid-hold reaches
     * neither, so the lease bounds how long its key can stay wedged. The lease is generous by
     * design — long enough that a merely-slow holder is never evicted.
     */
    List<Grant> expireLeases(java.time.LocalDateTime now);
}
