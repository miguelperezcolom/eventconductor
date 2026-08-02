package io.mateu.workflow.application.out;

/**
 * Another writer had the process, so this attempt did nothing and must be tried again.
 *
 * <p>Deliberately not swallowed like other failures. It is not a defect in the event — the event
 * is perfectly good and simply lost a race, which is what a consumer-group rebalance produces —
 * so the right answer is redelivery, not a dead letter and not a log line. Letting it propagate
 * is what stops the offset from advancing over work that never happened.
 */
public class ConcurrentProcessAccessException extends RuntimeException {

    public ConcurrentProcessAccessException(String processId, Throwable cause) {
        super("Concurrent write to process " + processId + " was rejected", cause);
    }
}
