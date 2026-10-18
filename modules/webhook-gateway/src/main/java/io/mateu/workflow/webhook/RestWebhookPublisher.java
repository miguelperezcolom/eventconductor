package io.mateu.workflow.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Hands the event to the engine's {@code POST /workflow/api/events/{destination}} as a structured
 * CloudEvent — for deployments without Kafka (embedded mode), or when the gateway should not hold
 * broker credentials. The engine answers what the event did. A refusal that would be the same on
 * redelivery (400, 401, 403, 404, 413, 422) is passed on with its status, so the sender does not retry
 * it; anything else that is not a 2xx — a 5xx, a timeout, an engine that cannot be reached — is a
 * {@code 502}, so the sender may redeliver. The engine's answer itself never reaches the sender.
 */
public class RestWebhookPublisher implements WebhookPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Engine answers that a redelivery of the same event would get again: passed on, not turned into a 502. */
    static final java.util.Set<Integer> DETERMINISTIC = java.util.Set.of(400, 401, 403, 404, 413, 422);
    private static final org.apache.commons.logging.Log log =
            org.apache.commons.logging.LogFactory.getLog(RestWebhookPublisher.class);

    private final HttpClient client;
    private final WebhookGatewayProperties.Engine engine;

    public RestWebhookPublisher(HttpClient client, WebhookGatewayProperties.Engine engine) {
        this.client = client;
        this.engine = engine;
    }

    @Override
    public void publish(String destination, WebhookEvent event) {
        if (engine.getUrl() == null || engine.getUrl().isBlank()) {
            throw new WebhookRejectedException(500, "workflow.webhooks.engine.url is not configured.");
        }
        var url = engine.getUrl().replaceAll("/+$", "") + "/workflow/api/events/" + destination
                + (event.key() == null ? "" : "?key=" + URLEncoder.encode(event.key(), StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(engine.getTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(envelope(event), StandardCharsets.UTF_8));
        if (engine.getApiKey() != null && !engine.getApiKey().isBlank()) {
            request.header("X-Api-Key", engine.getApiKey());
        }
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // The sender learns only that it may redeliver: the engine's address and its answer are
            // internal, and stay in this log.
            log.error("Could not hand webhook event " + event.id() + " to the engine at " + redacted(url) + ": " + e);
            throw new WebhookRejectedException(502, "The event could not be delivered; it may be redelivered.");
        }
        if (response.statusCode() / 100 != 2) {
            log.error("The engine answered " + response.statusCode() + " to " + redacted(url) + " for webhook event "
                    + event.id() + ": " + abbreviate(response.body()));
            if (DETERMINISTIC.contains(response.statusCode())) {
                // The engine will answer the same to the same delivery: a 502 would have the sender
                // redeliver it for nothing. Its status is passed on, its answer is not.
                throw new WebhookRejectedException(response.statusCode(),
                        "The event was refused (" + response.statusCode() + "); redelivering it unchanged will not help.");
            }
            throw new WebhookRejectedException(502, "The event could not be delivered; it may be redelivered.");
        }
    }

    /** The structured CloudEvent the engine's inject endpoint reads; {@code data} as parsed JSON when it is JSON. */
    static String envelope(WebhookEvent event) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("specversion", "1.0");
        envelope.put("id", event.id());
        envelope.put("source", event.source());
        envelope.put("type", event.type());
        if (event.subject() != null) {
            envelope.put("subject", event.subject());
        }
        if (event.time() != null) {
            envelope.put("time", event.time());
        }
        envelope.put("datacontenttype", event.dataContentType() == null ? "application/json" : event.dataContentType());
        if (event.extensions() != null) {
            envelope.putAll(event.extensions());
        }
        Object data = event.data();
        if (event.data() != null && !event.data().isBlank()) {
            try {
                data = JSON.readTree(event.data());
            } catch (Exception notJson) {
                data = event.data();
            }
        }
        envelope.put("data", data);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the CloudEvent", e);
        }
    }

    /** The URL without its query (the event's key). */
    private static String redacted(String url) {
        var q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
