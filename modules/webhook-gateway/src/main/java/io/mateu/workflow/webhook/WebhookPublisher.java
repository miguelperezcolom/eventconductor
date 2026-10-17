package io.mateu.workflow.webhook;

/** Where a read webhook goes: the destination's topic, or the engine's inject endpoint. */
public interface WebhookPublisher {

    /**
     * Publishes the event on {@code destination}.
     *
     * @throws WebhookRejectedException when it cannot (no topic for the destination, the broker or the
     *                                  engine unreachable): the sender gets an error and may redeliver
     */
    void publish(String destination, WebhookEvent event);
}
