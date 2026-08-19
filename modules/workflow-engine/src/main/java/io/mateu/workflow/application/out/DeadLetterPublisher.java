package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

public interface DeadLetterPublisher {

    /** Parks an event the engine understood and could not process. */
    void park(DomainEvent event, Throwable cause, String source);

    /**
     * Parks bytes the engine could not read as an event at all.
     *
     * <p>Separate from {@link #park} because there is no event to park — the payload never became
     * one, and what has to survive is exactly the bytes that arrived, so that whoever sent them can
     * be shown what the engine received.
     */
    void parkUnreadable(byte[] payload, Throwable cause, String source);
}
