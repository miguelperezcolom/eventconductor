package io.mateu.workflow.webhook;

/** How deliveries are counted, by endpoint and outcome; a no-op when there is no registry. */
@FunctionalInterface
public interface WebhookMetrics {

    WebhookMetrics NONE = (endpoint, outcome) -> { };

    /** One delivery of {@code endpoint}: published, unauthorized, rejected, unknown or error. */
    void count(String endpoint, String outcome);
}
