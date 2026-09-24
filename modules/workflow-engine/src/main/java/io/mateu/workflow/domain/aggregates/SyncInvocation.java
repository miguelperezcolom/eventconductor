package io.mateu.workflow.domain.aggregates;

/**
 * How instances of a definition can be invoked synchronously: started over HTTP by a caller that
 * waits, up to a deadline, for the reply a {@link StepType#REPLY} step emits.
 *
 * <p>Carried in the {@code .ec} file as {@code syncInvocation}. Absent — the default — means the
 * definition cannot be invoked synchronously; its asynchronous starts are unaffected either way,
 * and a REPLY step records its reply whichever way the process was started.
 *
 * @param enabled           whether the synchronous endpoint accepts this definition
 * @param onFailure         when a process that fails before replying answers its caller
 * @param onLockBusy        what an invocation does when the process-level lock is held
 * @param defaultDeadlineMs how long a caller waits when it does not say; 0 means the engine default
 */
public record SyncInvocation(
        boolean enabled,
        FailurePolicy onFailure,
        LockBusyPolicy onLockBusy,
        long defaultDeadlineMs
) {

    public SyncInvocation {
        onFailure = onFailure == null ? FailurePolicy.REPLY_IMMEDIATELY : onFailure;
        onLockBusy = onLockBusy == null ? LockBusyPolicy.WAIT : onLockBusy;
        defaultDeadlineMs = Math.max(0, defaultDeadlineMs);
    }

    /** When a process that fails before any REPLY answers its synchronous caller. */
    public enum FailurePolicy {
        /** As soon as the process fails — the reply says whether a compensation is under way. */
        REPLY_IMMEDIATELY,
        /** Once the saga rollback has finished (or there was nothing to roll back). */
        REPLY_AFTER_COMPENSATION
    }

    /** What an invocation does when the definition's process-level lock is held by another instance. */
    public enum LockBusyPolicy {
        /** Queue FIFO like any other instance; a long wait turns into a 202 through the deadline. */
        WAIT,
        /** Refuse the invocation without creating an instance, so the caller can retry. */
        FAIL
    }
}
