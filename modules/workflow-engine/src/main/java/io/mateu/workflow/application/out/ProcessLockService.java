package io.mateu.workflow.application.out;

/**
 * Per-process distributed (or local) mutual-exclusion lock.
 * Prevents concurrent modification of the same process from multiple threads or pods.
 */
public interface ProcessLockService {
    /** Returns true if the lock was acquired, false if already held by another thread/pod. */
    boolean tryLock(String processId);
    void unlock(String processId);
}
