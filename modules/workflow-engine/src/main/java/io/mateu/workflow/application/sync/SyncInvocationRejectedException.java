package io.mateu.workflow.application.sync;

/** An invocation the engine refuses before (or instead of) creating a process. */
public class SyncInvocationRejectedException extends RuntimeException {

    public enum Reason {
        /** The definition does not declare {@code syncInvocation.enabled}. */
        NOT_SYNC_INVOCABLE,
        /** The definition is disabled or archived and accepts no new instances. */
        NOT_ACCEPTING,
        /** The idempotency key was already used for a different request body. */
        IDEMPOTENCY_KEY_REUSED,
        /** Another process already holds the requested business key. */
        BUSINESS_KEY_TAKEN,
        /** The process-level lock is held and the definition says {@code onLockBusy: FAIL}. */
        LOCK_BUSY,
        /** Too many synchronous invocations are already waiting on this pod. */
        OVERLOADED,
        /**
         * Sharded: this idempotency key (or business key) is placed on another shard — a retry that
         * landed on the wrong shard. {@link #shard()} says which one owns it.
         */
        ON_ANOTHER_SHARD
    }

    private final Reason reason;
    private final String shard;

    public SyncInvocationRejectedException(Reason reason, String message) {
        this(reason, message, null);
    }

    public SyncInvocationRejectedException(Reason reason, String message, String shard) {
        super(message);
        this.reason = reason;
        this.shard = shard;
    }

    public Reason reason() {
        return reason;
    }

    /** The shard that owns the invocation, for {@link Reason#ON_ANOTHER_SHARD}; null otherwise. */
    public String shard() {
        return shard;
    }
}
