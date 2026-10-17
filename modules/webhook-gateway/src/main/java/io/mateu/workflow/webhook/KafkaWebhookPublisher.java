package io.mateu.workflow.webhook;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Writes the event on the destination's topic as a binary-mode CloudEvent — the attributes as
 * {@code ce_*} headers, the payload as the value, the key as the record key — exactly what the
 * engine's {@code PUBLISH_EVENT} writes and its inbound consumer reads. The send is awaited, so the
 * sender's 2xx means the broker has it.
 */
public class KafkaWebhookPublisher implements WebhookPublisher {

    private final KafkaTemplate<byte[], byte[]> template;
    private final WebhookDestinationsProperties destinations;
    private final java.time.Duration timeout;

    public KafkaWebhookPublisher(KafkaTemplate<byte[], byte[]> template, WebhookDestinationsProperties destinations,
                                 java.time.Duration timeout) {
        this.template = template;
        this.destinations = destinations;
        this.timeout = timeout;
    }

    @Override
    public void publish(String destination, WebhookEvent event) {
        var topic = destinations.topicOf(destination);
        if (topic == null) {
            throw new WebhookRejectedException(500, "Destination '" + destination + "' has no topic configured"
                    + " (workflow.events.destinations." + destination + ".topic).");
        }
        try {
            template.send(record(topic, event)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new WebhookRejectedException(502, "Could not publish on " + topic + ": " + e.getMessage());
        }
    }

    /** The record for {@code event}, package-private for the tests. */
    static ProducerRecord<byte[], byte[]> record(String topic, WebhookEvent event) {
        var headers = new ArrayList<org.apache.kafka.common.header.Header>();
        headers.add(header("contentType", event.dataContentType() == null ? "application/json" : event.dataContentType()));
        headers.add(header("ce_specversion", "1.0"));
        headers.add(header("ce_id", event.id()));
        headers.add(header("ce_source", event.source()));
        headers.add(header("ce_type", event.type()));
        if (event.subject() != null) {
            headers.add(header("ce_subject", event.subject()));
        }
        if (event.time() != null) {
            headers.add(header("ce_time", event.time()));
        }
        if (event.dataContentType() != null) {
            headers.add(header("ce_datacontenttype", event.dataContentType()));
        }
        if (event.extensions() != null) {
            event.extensions().forEach((name, value) -> headers.add(header("ce_" + name, value)));
        }
        var key = event.key() == null || event.key().isBlank() ? null : event.key().getBytes(StandardCharsets.UTF_8);
        var value = event.data() == null ? new byte[0] : event.data().getBytes(StandardCharsets.UTF_8);
        return new ProducerRecord<>(topic, null, key, value, headers);
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }
}
