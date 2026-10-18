package io.mateu.workflow.webhook;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POST <workflow.webhooks.path>/{endpoint}}: the webhook is read by the endpoint's provider
 * (signature checked, attributes mapped) and published on the endpoint's destination. 202 with the
 * event's id, type and destination; 401 for a bad signature, 404 for an unknown endpoint, 400 for a
 * delivery the provider cannot read, 5xx when it could not be published — the sender may redeliver,
 * and the engine uses each delivery once.
 */
@RestController
public class WebhookController {

    private final WebhookGatewayProperties properties;
    private final Map<WebhookGatewayProperties.Provider, WebhookProvider> providers;
    private final WebhookPublisher publisher;
    private final WebhookMetrics metrics;

    public WebhookController(WebhookGatewayProperties properties, Map<WebhookGatewayProperties.Provider, WebhookProvider> providers,
                             WebhookPublisher publisher, WebhookMetrics metrics) {
        this.properties = properties;
        this.providers = providers;
        this.publisher = publisher;
        this.metrics = metrics == null ? WebhookMetrics.NONE : metrics;
    }

    @PostMapping("${workflow.webhooks.path:/webhooks}/{endpoint}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable("endpoint") String endpointName,
                                                       @RequestHeader HttpHeaders headers,
                                                       @RequestBody(required = false) byte[] body) {
        var endpoint = properties.endpoint(endpointName);
        if (endpoint == null) {
            // Not the requested name: it comes from the URL, and one meter series per name a client
            // invents would grow without bound. Every configured endpoint keeps its own tag.
            metrics.count(WebhookMetrics.UNKNOWN_ENDPOINT, "unknown");
            throw new WebhookRejectedException(404, "No webhook endpoint '" + endpointName + "' is configured.");
        }
        if (endpoint.getDestination() == null || endpoint.getDestination().isBlank()) {
            metrics.count(endpointName, "error");
            throw new WebhookRejectedException(500, "Endpoint '" + endpointName + "' names no destination.");
        }
        var provider = providers.get(endpoint.getProvider());
        WebhookEvent event;
        try {
            event = provider.read(endpointName, endpoint, lowerCase(headers), body);
        } catch (WebhookRejectedException e) {
            metrics.count(endpointName, e.status() == 401 ? "unauthorized" : "rejected");
            throw e;
        }
        try {
            publisher.publish(endpoint.getDestination(), event);
        } catch (WebhookRejectedException e) {
            metrics.count(endpointName, "error");
            throw e;
        }
        metrics.count(endpointName, "published");
        var answer = new LinkedHashMap<String, Object>();
        answer.put("endpoint", endpointName);
        answer.put("destination", endpoint.getDestination());
        answer.put("id", event.id());
        answer.put("type", event.type());
        return ResponseEntity.accepted().body(answer);
    }

    @ExceptionHandler(WebhookRejectedException.class)
    ResponseEntity<ProblemDetail> onRejected(WebhookRejectedException e) {
        return ResponseEntity.status(e.status()).body(ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.valueOf(e.status()), e.getMessage()));
    }

    /** The first value of each header, by lower-case name. */
    static Map<String, String> lowerCase(HttpHeaders headers) {
        var map = new LinkedHashMap<String, String>();
        if (headers != null) {
            headers.forEach((name, values) -> {
                if (values != null && !values.isEmpty()) {
                    map.put(name.toLowerCase(Locale.ROOT), values.get(0));
                }
            });
        }
        return map;
    }
}
