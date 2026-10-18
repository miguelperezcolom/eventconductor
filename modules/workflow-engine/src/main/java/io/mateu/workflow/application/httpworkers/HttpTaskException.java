package io.mateu.workflow.application.httpworkers;

/** Why the worker API refused a call; each reason maps to one HTTP status. */
public class HttpTaskException extends RuntimeException {

    public enum Reason {
        /** 400: the request is malformed. */
        INVALID,
        /** 404: the task is gone — answered already, cancelled and dropped, or never queued. */
        NOT_FOUND,
        /** 409: the reply is for a dispatch the engine has since replaced (the step ran again). */
        STALE_ATTEMPT,
        /** 409: the lease was taken by another worker after it expired. */
        LEASE_LOST,
        /** 409: the engine cancelled the task; its result is no longer wanted. */
        CANCELLED,
        /** 429: too many activations are waiting on this node. */
        OVERLOADED,
        /** 503: the engine did not take the reply; retry it. */
        UNAVAILABLE
    }

    private final Reason reason;

    public HttpTaskException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HttpTaskException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
