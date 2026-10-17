package io.mateu.workflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub's webhooks (<a href="https://docs.github.com/webhooks">docs.github.com/webhooks</a>):
 *
 * <ul>
 *   <li>{@code X-Hub-Signature-256} — {@code sha256=<hex HMAC-SHA256 of the body>} under the secret
 *       given to GitHub; required, a delivery without a valid one is answered 401.</li>
 *   <li>{@code X-GitHub-Delivery} — the delivery's GUID, the CloudEvents id: GitHub's redeliveries
 *       carry the same one, so the engine uses each delivery once.</li>
 *   <li>{@code X-GitHub-Event} — the event name ({@code push}, {@code pull_request}, {@code ping}…),
 *       which with the payload's {@code action} ({@code opened}, {@code closed}…) makes the type:
 *       {@code <typePrefix><event>[.<action>]} — {@code com.github.push},
 *       {@code com.github.pull_request.opened}. A trigger on {@code com.github.pull_request.*} gets
 *       every pull-request action.</li>
 *   <li>The subject, and the record key, is the repository's {@code full_name} when the payload has one
 *       ({@code acme/shop}); the extensions carry {@code githubevent} and, when present,
 *       {@code githubaction}, the repository and the sender's login.</li>
 * </ul>
 */
public class GitHubWebhookProvider implements WebhookProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public WebhookEvent read(String endpointName, WebhookGatewayProperties.Endpoint endpoint, Map<String, String> headers,
                             byte[] body) {
        if (endpoint.getSecret() == null || endpoint.getSecret().isBlank()) {
            throw new WebhookRejectedException(500, "Endpoint '" + endpointName + "' has no secret configured:"
                    + " GitHub webhooks are only accepted signed (workflow.webhooks.endpoints." + endpointName + ".secret).");
        }
        if (!Signatures.matches(endpoint.getSecret(), body, headers.get("x-hub-signature-256"))) {
            throw new WebhookRejectedException(401, "Missing or invalid X-Hub-Signature-256.");
        }
        var event = headers.get("x-github-event");
        if (event == null || event.isBlank()) {
            throw new WebhookRejectedException(400, "Missing X-GitHub-Event header.");
        }
        var delivery = headers.get("x-github-delivery");
        var id = delivery == null || delivery.isBlank() ? java.util.UUID.randomUUID().toString() : delivery.trim();
        var text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        JsonNode payload;
        try {
            payload = text.isBlank() ? JSON.nullNode() : JSON.readTree(text);
        } catch (Exception e) {
            throw new WebhookRejectedException(400, "The body is not JSON: " + e.getMessage());
        }
        var action = text(payload, "action");
        var type = (endpoint.getTypePrefix() == null ? "" : endpoint.getTypePrefix()) + event.trim()
                + (action == null ? "" : "." + action);
        var repository = payload.isObject() && payload.has("repository") ? text(payload.get("repository"), "full_name") : null;
        var sender = payload.isObject() && payload.has("sender") ? text(payload.get("sender"), "login") : null;
        var extensions = new LinkedHashMap<String, String>();
        extensions.put("githubevent", event.trim());
        if (action != null) {
            extensions.put("githubaction", action);
        }
        if (repository != null) {
            extensions.put("repository", repository);
        }
        if (sender != null) {
            extensions.put("sender", sender);
        }
        var source = endpoint.getSource() == null || endpoint.getSource().isBlank() ? endpointName : endpoint.getSource();
        return new WebhookEvent(id, source, type, repository, Instant.now().toString(), repository,
                "application/json", text, extensions);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }
}
