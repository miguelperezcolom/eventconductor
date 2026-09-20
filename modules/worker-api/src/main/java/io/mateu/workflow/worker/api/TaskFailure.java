package io.mateu.workflow.worker.api;

/**
 * A declared business failure of a task — one of the {@code errors} in its contract. Throwing it
 * from a {@link TaskHandler} fails the step with the {@link #code()} as the reason (via the
 * four-argument {@code WorkerReply.failed}), telling the process this outcome apart from an
 * unexpected exception. The generated code turns each contract error into a subclass.
 */
public class TaskFailure extends RuntimeException {

    private final String code;

    public TaskFailure(String code) {
        this(code, code);
    }

    public TaskFailure(String code, String reason) {
        super(reason);
        this.code = code;
    }

    /** The contract error code (a valid Java identifier), reported as the failure reason. */
    public String code() {
        return code;
    }

    /** The full reason reported to the engine: {@code <code>: <message>} when they differ. */
    public String reason() {
        return getMessage() == null || getMessage().equals(code) ? code : code + ": " + getMessage();
    }
}
