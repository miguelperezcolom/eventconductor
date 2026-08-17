package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * Sends an event out to the workers.
 *
 * <p>The destination is passed in rather than derived here, because it belongs to the step: a
 * definition may send one step's task to a worker pool of its own. {@code topic} is that step's
 * {@code topic} field, and a null or blank one means the default {@code downstream} destination —
 * which is where everything went before per-step routing existed, and where the great majority of
 * steps still go.
 *
 * <p>It is a parameter on every call, not an overload with a default, so that a new call site has
 * to say where its event goes. The one that must not get this wrong is cancellation: a
 * {@code TaskCancellationRequested} has to reach the worker that is running the task, so it goes
 * to the topic that task was dispatched to. Sent to the default while the task ran on a pool of
 * its own, it would reach nobody and the step would run to its timeout instead of stopping.
 *
 * <p>Only Kafka mode routes. Embedded mode has one in-process {@link EmbeddedTaskExecutor} and no
 * transport to route over, so it takes every task whatever the topic says.
 */
public interface DownstreamEventPublisher {

    /** The destination a step with no {@code topic} of its own is dispatched to. */
    String DEFAULT_TOPIC = "downstream";

    /**
     * @param event the event to deliver to a worker
     * @param topic the step's destination; null or blank means {@link #DEFAULT_TOPIC}
     */
    void publish(DomainEvent event, String topic);

    /** Resolves a step's {@code topic} to the destination it is actually sent to. */
    static String destinationFor(String topic) {
        return (topic == null || topic.isBlank()) ? DEFAULT_TOPIC : topic;
    }
}
