package io.mateu.workflow.webhook;

import java.util.Map;

/**
 * How one kind of sender's webhook is read: its signature verified and its attributes mapped onto a
 * {@link WebhookEvent}.
 */
public interface WebhookProvider {

    /**
     * Reads a delivery.
     *
     * @param endpointName the endpoint's name, the last segment of the URL it arrived on
     * @param endpoint     its configuration
     * @param headers      the request headers by lower-case name (the first value of each)
     * @param body         the raw body
     * @throws WebhookRejectedException when the delivery is not to be published (bad signature, unreadable)
     */
    WebhookEvent read(String endpointName, WebhookGatewayProperties.Endpoint endpoint, Map<String, String> headers, byte[] body);
}
