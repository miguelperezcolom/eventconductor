package io.mateu.workflow.application.out;

/**
 * Per-process mutual exclusion. Prevents two threads or two pods from working on the same
 * process at once.
 *
 * <p>Scoped to a callback rather than exposed as lock/unlock on purpose: in JPA mode exclusivity
 * is a row lock held by the transaction the action runs in, so its lifetime <em>is</em> the
 * action. There is no lock to leak, no session to keep alive on the side, and no watchdog needed
 * to clean up after an action that never released one.
 */
public interface ProcessLockService {

    /**
     * Runs the action with exclusive access to the process. Returns false — <b>without running
     * it</b> — if exclusivity could not be obtained before the timeout, which callers treat as
     * "another node has this one", not as an error to retry in a loop.
     */
    boolean runExclusively(String processId, Runnable action);

    /**
     * Runs the action with exclusive access to the process, or fails with
     * {@link ConcurrentProcessAccessException} — without running it — when exclusivity could not
     * be had. For the callers to whom "someone else has this process" must not be a silent no-op:
     * an operator action has to learn it did nothing, and an event that triggered it has to be
     * redelivered ({@code EventFailures} treats the exception as retryable).
     *
     * <p>Static rather than a default method on purpose: tests mock this interface, and a mock
     * would swallow a default method instead of routing it through {@link #runExclusively}.
     */
    static void runExclusivelyOrThrow(ProcessLockService lockService, String processId, Runnable action) {
        if (!lockService.runExclusively(processId, action)) {
            throw new ConcurrentProcessAccessException(processId, null);
        }
    }
}
