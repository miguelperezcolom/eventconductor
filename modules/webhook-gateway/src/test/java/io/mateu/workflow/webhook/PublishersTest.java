package io.mateu.workflow.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the two transports write: the binary record, and the structured envelope the engine's inject reads. */
class PublishersTest {

    static final WebhookEvent EVENT = new WebhookEvent("d-1", "github", "com.github.push", "acme/shop",
            "2026-10-17T10:00:00Z", "acme/shop", "application/json", "{\"ref\": \"refs/heads/main\"}",
            Map.of("githubevent", "push"));

    @Test
    void theKafkaRecordIsABinaryCloudEventKeyedBySubject() {
        var record = KafkaWebhookPublisher.record("github-events", EVENT);

        assertThat(record.topic()).isEqualTo("github-events");
        assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("acme/shop");
        assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo("{\"ref\": \"refs/heads/main\"}");
        var headers = new java.util.HashMap<String, String>();
        record.headers().forEach(h -> headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
        assertThat(headers).containsEntry("ce_specversion", "1.0").containsEntry("ce_id", "d-1")
                .containsEntry("ce_source", "github").containsEntry("ce_type", "com.github.push")
                .containsEntry("ce_subject", "acme/shop").containsEntry("ce_time", "2026-10-17T10:00:00Z")
                .containsEntry("ce_datacontenttype", "application/json").containsEntry("contentType", "application/json")
                .containsEntry("ce_githubevent", "push");

        var keyless = KafkaWebhookPublisher.record("t", new WebhookEvent("i", "s", "t", null, null, null, null, null, null));
        assertThat(keyless.key()).isNull();
        assertThat(keyless.value()).isEmpty();
    }

    @Test
    void aDestinationWithoutATopicIsAnError() {
        var publisher = new KafkaWebhookPublisher(null, new WebhookDestinationsProperties(), java.time.Duration.ofSeconds(1));
        assertThatThrownBy(() -> publisher.publish("nowhere", EVENT))
                .isInstanceOf(WebhookRejectedException.class)
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(500));
    }

    @Test
    void theRestEnvelopeIsAStructuredCloudEventWithParsedData() throws Exception {
        var envelope = new ObjectMapper().readValue(RestWebhookPublisher.envelope(EVENT), Map.class);

        assertThat(envelope).containsEntry("specversion", "1.0").containsEntry("id", "d-1").containsEntry("source", "github")
                .containsEntry("type", "com.github.push").containsEntry("subject", "acme/shop")
                .containsEntry("githubevent", "push").containsEntry("datacontenttype", "application/json");
        assertThat(envelope.get("data")).isEqualTo(Map.of("ref", "refs/heads/main"));

        var text = new WebhookEvent("i", "s", "t", null, null, null, "text/plain", "hello", Map.of());
        assertThat(new ObjectMapper().readValue(RestWebhookPublisher.envelope(text), Map.class)).containsEntry("data", "hello");
    }

    @Test
    void theRestPublisherPostsToTheEnginesInjectWithTheKeyAndApiKey() throws Exception {
        var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workflow/api/events/github-events", exchange -> {
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("X-Api-Key") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var answer = "{\"receipts\": []}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.createContext("/workflow/api/events/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var engine = new WebhookGatewayProperties.Engine();
            engine.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            engine.setApiKey("k");
            var publisher = new RestWebhookPublisher(HttpClient.newHttpClient(), engine);

            publisher.publish("github-events", EVENT);

            assertThat(seen.get()).startsWith("POST /workflow/api/events/github-events?key=acme%2Fshop k ")
                    .contains("\"type\":\"com.github.push\"");
            assertThatThrownBy(() -> publisher.publish("broken", EVENT))
                    .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(502));
            engine.setUrl("http://127.0.0.1:1/");
            assertThatThrownBy(() -> publisher.publish("github-events", EVENT))
                    .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(502));
            engine.setUrl(null);
            assertThatThrownBy(() -> publisher.publish("github-events", EVENT))
                    .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(500));
        } finally {
            server.stop(0);
        }
    }
}
