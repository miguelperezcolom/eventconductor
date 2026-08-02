package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * Parks an event the engine cannot process, so it can be looked at and replayed instead of
 * disappearing into a log line.
 *
 * <p>Only for failures that will never succeed. Anything that could work later is retried
 * instead — see {@code EventFailures}.
 */
public interface DeadLetterPublisher {

    /**
     * @param event  parked unchanged, so replaying it is republishing it
     * @param source the destination it arrived on, recorded for whoever replays it
     */
    void park(DomainEvent event, Throwable cause, String source);
}
