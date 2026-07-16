package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessLockService;

/**
 * Lock acquisition with bounded retry. A busy per-process lock usually means another
 * thread/pod is mid-flight on the same process for a few milliseconds — giving up
 * immediately would drop the triggering event, wedging the process.
 */
public final class ProcessLocks {

    private static final int MAX_ATTEMPTS = 7;
    private static final long INITIAL_DELAY_MS = 50;
    private static final long MAX_DELAY_MS = 2000;

    private ProcessLocks() {
    }

    /** Tries to acquire the process lock, retrying with backoff for a few seconds. */
    public static boolean lockWithRetry(ProcessLockService lockService, String processId) {
        long delay = INITIAL_DELAY_MS;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (lockService.tryLock(processId)) {
                return true;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            delay = Math.min(delay * 2, MAX_DELAY_MS);
        }
        return lockService.tryLock(processId);
    }
}
