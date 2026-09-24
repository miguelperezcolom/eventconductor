package io.mateu.workflow.worker.api;

/**
 * A declared business failure of a task — one of the {@code errors} in its contract. Throwing it
 * from a {@link TaskHandler} fails the step with the {@link #code()} as the reason (via the
 * four-argument {@code WorkerReply.failed}), telling the process this outcome apart from an
 * unexpected exception. The generated code turns each contract error into a subclass.
 */
public class TaskFailure extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public TaskFailure(String code) {
        this(code, code);
    }

    public TaskFailure(String code, String reason) {
        this(code, reason, true);
    }

    /**
     * @param retryable false for a failure retrying cannot fix: the engine then fails the step at
     *                  once instead of spending its {@code retries} (see {@code FailureMarkers})
     */
    public TaskFailure(String code, String reason, boolean retryable) {
        super(reason);
        this.code = code;
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** The contract error code (a valid Java identifier), reported as the failure reason. */
    public String code() {
        return code;
    }

    /** The full reason reported to the engine: {@code <code>: <message>} when they differ. */
    public String reason() {
        var reason = getMessage() == null || getMessage().equals(code) ? code : code + ": " + getMessage();
        return retryable ? reason : io.mateu.workflow.worker.FailureMarkers.nonRetryable(reason);
    }
}
