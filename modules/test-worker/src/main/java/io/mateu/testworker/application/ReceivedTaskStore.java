package io.mateu.testworker.application;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.testworker.domain.ReceivedTask;

/** The log of tasks this worker has been given. Written by the worker, read by the UI. */
public interface ReceivedTaskStore extends CrudStore<ReceivedTask> {

    /**
     * How many times this task execution has already been handed to the worker — 0 the first time.
     * The attempt number is this plus one, and it is what {@code failuresBeforeSuccess} counts.
     *
     * <p>Keyed by the task execution, because that is how the engine retries: a retry re-dispatches
     * the <b>same</b> {@code taskExecutionId} and counts the attempts itself, on the step
     * execution. This was written the other way round first — counting the step's rows within the
     * process, on the assumption that a retry arrives as a new task execution — and DIST-13 showed
     * what that costs: the retry overwrote the row it was counting, the count never left 1, and a
     * scenario asking to fail twice failed until the engine gave up.
     *
     * <p>The honest caveat: a Kafka redelivery of a task the worker already saw is counted too.
     * Nothing on {@code TaskExecutionRequested} distinguishes it from a retry, so no worker can
     * tell them apart — and a redelivery only happens when a worker throws, which for this one
     * means the broker refused its reply.
     */
    int previousDeliveriesOf(String taskExecutionId);
}
