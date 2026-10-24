package io.mateu.workflow.events;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventMessagesTest {

    private static final ExternalEventRequested EVENT = new ExternalEventRequested("se-1", "bookings",
            "booking.confirmed", "B-1", null, "{\"id\":\"B-1\"}", "p-1", "wd", "announce", "BK-1", "2026-09-24T10:00:00Z");

    private static String text(Message<byte[]> message) {
        return new String(message.getPayload(), StandardCharsets.UTF_8);
    }

    @Test
    void binaryModeCarriesTheAttributesAsHeadersKeyedByTheEventsKey() {
        var message = CloudEventMessages.messageFor(EVENT, "binary");
        assertThat(text(message)).isEqualTo("{\"id\":\"B-1\"}");
        assertThat(message.getHeaders()).containsEntry("ce_specversion", "1.0").containsEntry("ce_id", "se-1")
                .containsEntry("ce_type", "booking.confirmed").containsEntry("ce_source", "eventconductor/wd")
                .containsEntry("ce_subject", "BK-1").containsEntry("ce_processid", "p-1")
                .containsEntry("ce_stepid", "announce").containsEntry("ce_time", "2026-09-24T10:00:00Z");
        assertThat(new String((byte[]) message.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8)).isEqualTo("B-1");
    }

    @Test
    void structuredModeIsOneJsonEnvelopeAndPlainIsThePayloadAlone() {
        var structured = text(CloudEventMessages.messageFor(EVENT, "structured"));
        assertThat(structured).startsWith("{\"specversion\":\"1.0\"").contains("\"data\":{\"id\":\"B-1\"}")
                .contains("\"datacontenttype\":\"application/json\"");
        var plain = CloudEventMessages.messageFor(EVENT, "plain");
        assertThat(text(plain)).isEqualTo("{\"id\":\"B-1\"}");
        assertThat(plain.getHeaders()).doesNotContainKey("ce_id");
    }

    @Test
    void anEventWithoutDataOrBusinessKeyStillHasARecord() {
        var bare = new ExternalEventRequested("se-2", "bookings", "booking.cancelled", null, null, null,
                "p-2", "wd", "announce", null, "2026-09-24T10:00:00Z");
        var binary = CloudEventMessages.messageFor(bare, "binary");
        assertThat(binary.getPayload()).isEmpty();
        assertThat(binary.getHeaders()).doesNotContainKey("ce_subject");
        assertThat(new String((byte[]) binary.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8))
                .as("no key of its own: the process id").isEqualTo("p-2");
        assertThat(CloudEventMessages.messageFor(bare, "plain").getPayload()).isEmpty();
        assertThat(text(CloudEventMessages.messageFor(bare, "structured"))).contains("\"data\":null");
    }

    @Test
    void theFormatIsTheEventsOwnElseItsDestinationsElseBinary() {
        assertThat(CloudEventMessages.formatOf(EVENT, "plain")).isEqualTo("plain");
        assertThat(CloudEventMessages.formatOf(EVENT, " ")).isEqualTo("binary");
        assertThat(CloudEventMessages.formatOf(EVENT, null)).isEqualTo("binary");
        var structured = new ExternalEventRequested("se-1", "bookings", "booking.confirmed", "B-1", "structured",
                "{}", "p-1", "wd", "announce", "BK-1", "2026-09-24T10:00:00Z");
        assertThat(CloudEventMessages.formatOf(structured, "plain")).isEqualTo("structured");
    }
}
