package io.mateu.workflow.application.updates;

/**
 * An update the engine would not take, with why — mapped to an HTTP status by the REST door, to a sentence
 * by the MCP tools. Nothing about the process changed.
 */
public class ProcessUpdateRejectedException extends RuntimeException {

    public enum Reason {
        /** No such process (404). */
        NO_SUCH_PROCESS,
        /** The process's definition version declares no update of that name, or no such definition (404). */
        NO_SUCH_UPDATE,
        /** The payload is not JSON, fails the update's {@code accepts} schema, or is over the input limits (400). */
        INVALID_PAYLOAD,
        /** The process is not accepting the update: not RUNNING/PENDING, or the step it needs is not waiting (409). */
        NOT_ACCEPTING,
        /** The business key belongs to a process of another definition (409). */
        BUSINESS_KEY_TAKEN,
        /** A {@code validate} guard said no (422). */
        REFUSED,
        /** The idempotency key was used for a different request (422). */
        IDEMPOTENCY_KEY_REUSED,
        /** An expression of the update could not be evaluated (500). */
        FAILED,
        /** Too many callers waiting on this node (429). */
        OVERLOADED,
        /** The broker could not take the command (503). */
        UNAVAILABLE
    }

    private final Reason reason;
    private final transient ProcessUpdate update;

    public ProcessUpdateRejectedException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ProcessUpdateRejectedException(Reason reason, String message, ProcessUpdate update) {
        super(message);
        this.reason = reason;
        this.update = update;
    }

    public Reason reason() {
        return reason;
    }

    /** The recorded update this refusal is the outcome of, when there is one. */
    public ProcessUpdate update() {
        return update;
    }
}
