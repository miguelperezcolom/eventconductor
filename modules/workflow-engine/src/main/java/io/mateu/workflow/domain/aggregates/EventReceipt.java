package io.mateu.workflow.domain.aggregates;

import java.time.Instant;

/**
 * What one receiver did with one domain event — the idempotency record of the inbound path, and a row
 * of the "received events" listing.
 *
 * @param receiptKey     SHA-256 of the receiver and the event's source and id (an ERROR is kept under a
 *                       key of its own, so a failed attempt never blocks a later one)
 * @param receiver       {@code trigger:<workflow>/<trigger>} or {@code wait:<messageName>/<correlationKey>}
 * @param kind           which kind of receiver
 * @param outcome        what it did
 * @param businessKey    TRIGGER: the business key of the process it started
 * @param messageName    WAIT: the message the event became
 * @param correlationKey WAIT: the key it carried
 * @param detail         ERROR: why; otherwise a short note, may be null
 */
public record EventReceipt(String receiptKey, String receiver, Kind kind, String destination, String eventType,
                           String eventSource, String eventId, Outcome outcome, String workflowId, String triggerId,
                           String businessKey, String messageName, String correlationKey, String detail,
                           Instant receivedAt) {

    /** A trigger of a definition, or the message a waiting step turns the event into. */
    public enum Kind { TRIGGER, WAIT }

    /** A process was started, a message was delivered, or the receiver could not use the event. */
    /**
     * What the receiver did. STARTED / DELIVERED / ERROR, and — a trigger whose start met a
     * {@code startPolicy} (2.33) — DEBOUNCED (absorbed into a pending start), THROTTLED (queued until its
     * window has room), DROPPED (over a throttle that drops), SKIPPED (a singleton already running for the key)
     * and REPLACED (started, the running one cancelled).
     */
    public enum Outcome { STARTED, DELIVERED, ERROR, DEBOUNCED, THROTTLED, DROPPED, SKIPPED, REPLACED }
}
