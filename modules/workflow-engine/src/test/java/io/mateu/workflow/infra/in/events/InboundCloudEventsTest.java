package io.mateu.workflow.infra.in.events;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InboundCloudEventsTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsBinaryContentMode() {
        var event = InboundCloudEvents.fromRecord("bookings", "booking-events", 2, 40, bytes("B-1"), bytes("{\"a\":1}"),
                Map.of("ce_specversion", "1.0", "ce_id", "e-1", "ce_source", "pms", "ce_type", "com.acme.x",
                        "ce_subject", "s", "ce_time", "2026-10-15T10:00:00Z", "ce_tenant", "acme", "contentType", "application/json"));
        assertThat(event.destination()).isEqualTo("bookings");
        assertThat(event.id()).isEqualTo("e-1");
        assertThat(event.source()).isEqualTo("pms");
        assertThat(event.type()).isEqualTo("com.acme.x");
        assertThat(event.subject()).isEqualTo("s");
        assertThat(event.key()).isEqualTo("B-1");
        assertThat(event.data()).isEqualTo("{\"a\":1}");
        assertThat(event.dataContentType()).isEqualTo("application/json");
        assertThat(event.extensions()).containsExactly(Map.entry("tenant", "acme"));
    }

    @Test
    void binaryModeWithoutCeDatacontenttypeFallsBackToTheStandardContentTypeHeaderInAnyCase() {
        // The CloudEvents Kafka binding writes datacontenttype as the plain content-type header.
        var lower = InboundCloudEvents.fromRecord("bookings", "booking-events", 0, 1, null, bytes("<a/>"),
                Map.of("ce_id", "e-1", "ce_source", "pms", "content-type", "application/xml"));
        assertThat(lower.dataContentType()).isEqualTo("application/xml");
        var mixed = InboundCloudEvents.fromRecord("bookings", "booking-events", 0, 2, null, bytes("<a/>"),
                Map.of("ce_id", "e-2", "ce_source", "pms", "Content-Type", "application/xml"));
        assertThat(mixed.dataContentType()).isEqualTo("application/xml");
        // ce_datacontenttype, when present, wins.
        var explicit = InboundCloudEvents.fromRecord("bookings", "booking-events", 0, 3, null, bytes("{}"),
                Map.of("ce_id", "e-3", "ce_source", "pms", "ce_datacontenttype", "application/json",
                        "content-type", "application/xml"));
        assertThat(explicit.dataContentType()).isEqualTo("application/json");
        // And a plain record reads it in any case too.
        var plain = InboundCloudEvents.fromRecord("bookings", "booking-events", 0, 4, null, bytes("x"),
                Map.of("Content-Type", "text/plain"));
        assertThat(plain.dataContentType()).isEqualTo("text/plain");
    }

    @Test
    void readsStructuredContentMode() {
        var value = "{\"specversion\":\"1.0\",\"id\":\"e-2\",\"source\":\"pms\",\"type\":\"t\",\"tenant\":\"acme\","
                + "\"data\":{\"bookingId\":\"B-2\"}}";
        var event = InboundCloudEvents.fromRecord("bookings", "booking-events", 0, 1, null, bytes(value),
                Map.of("contentType", "application/cloudevents+json"));
        assertThat(event.id()).isEqualTo("e-2");
        assertThat(event.type()).isEqualTo("t");
        assertThat(event.data()).isEqualTo("{\"bookingId\":\"B-2\"}");
        assertThat(event.extensions()).containsEntry("tenant", "acme");
        assertThat(event.key()).isNull();
    }

    @Test
    void aPlainRecordGetsAnIdFromItsPosition() {
        var event = InboundCloudEvents.fromRecord("bookings", "booking-events", 3, 77, bytes("k"), bytes("{\"x\":1}"), Map.of());
        assertThat(event.id()).isEqualTo("booking-events-3-77");
        assertThat(event.source()).isEqualTo("kafka://booking-events");
        assertThat(event.type()).isNull();
        assertThat(event.data()).isEqualTo("{\"x\":1}");
    }

    @Test
    void aStructuredBodyKeepsTextDataAndReadsAKey() {
        var event = InboundCloudEvents.fromStructured("d", Map.of("id", "i", "source", "s", "data", "plain text", "key", "K"), null);
        assertThat(event.data()).isEqualTo("plain text");
        assertThat(event.key()).isEqualTo("K");
        assertThat(InboundCloudEvents.fromStructured("d", Map.of("id", "i", "source", "s", "key", "K"), "P").key()).isEqualTo("P");
    }
}
