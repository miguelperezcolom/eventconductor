package io.mateu.workflow.webhook;

/** How deliveries are counted, by endpoint and outcome; a no-op when there is no registry. */
@FunctionalInterface
public interface WebhookMetrics {

    WebhookMetrics NONE = (endpoint, outcome) -> { };

    /**
     * The {@code endpoint} tag of every delivery to an endpoint that is not configured (outcome
     * {@code unknown}): one series for all of them, whatever URL the client made up. The outcome keeps it
     * apart even from an endpoint configured under the same name.
     */
    String UNKNOWN_ENDPOINT = "_unknown";

    /** One delivery of {@code endpoint}: published, unauthorized, rejected, unknown or error. */
    void count(String endpoint, String outcome);
}
