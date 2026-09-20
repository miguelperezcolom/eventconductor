package io.mateu.workflow.worker.api;

/**
 * Thrown by a {@link TaskReplySink} when the transport refuses the reply after its own retries — a
 * broker that will not take the message. It is the one exception the {@link TaskDispatcher} lets
 * propagate: the caller (the Kafka consumer) must not commit the offset, so the task is redelivered
 * and answered again, rather than lost. Every other failure has already been turned into a reply.
 */
public class ReplyNotAcceptedException extends RuntimeException {

    public ReplyNotAcceptedException(String message) {
        super(message);
    }

    public ReplyNotAcceptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
