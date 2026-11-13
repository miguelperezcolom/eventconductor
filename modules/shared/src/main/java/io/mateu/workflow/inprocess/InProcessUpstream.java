package io.mateu.workflow.inprocess;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * The engine's {@code upstream} topic when there is no broker: a module running in the same JVM as an
 * embedded engine ({@code workflow.mode=embedded}) hands it what it would otherwise publish on
 * {@code upstream} — a worker's {@code TaskStatusChanged}, a {@code TaskLogEmitted} line — and the
 * engine processes it on the calling thread.
 *
 * <p>Implemented by the embedded engine. Here, in {@code shared}, so that a module such as the forms
 * engine can reach an in-process engine without depending on it.
 */
public interface InProcessUpstream {

    /**
     * Processes {@code event} as if it had arrived on {@code upstream}.
     *
     * @throws RuntimeException when the engine could not process it — the caller decides whether to retry
     */
    void publish(DomainEvent event);
}
