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
}
