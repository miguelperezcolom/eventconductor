package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalEventSenderTest {

    private static final ExternalEventRequested EVENT = new ExternalEventRequested("se-1", "bookings",
            "booking.confirmed", "B-1", null, "{\"id\":\"B-1\"}", "p-1", "wd", "announce", "BK-1", "2026-09-24T10:00:00Z");

    private static String text(Message<byte[]> message) {
        return new String(message.getPayload(), StandardCharsets.UTF_8);
    }

    @Test
    void binaryModeCarriesTheAttributesAsHeaders() {
        var message = ExternalEventSender.messageFor(EVENT, "binary");
        assertThat(text(message)).isEqualTo("{\"id\":\"B-1\"}");
        assertThat(message.getHeaders()).containsEntry("ce_specversion", "1.0").containsEntry("ce_id", "se-1")
                .containsEntry("ce_type", "booking.confirmed").containsEntry("ce_source", "eventconductor/wd")
                .containsEntry("ce_subject", "BK-1").containsEntry("ce_processid", "p-1");
        assertThat(new String((byte[]) message.getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8)).isEqualTo("B-1");
    }

    @Test
    void structuredModeIsOneJsonEnvelopeAndPlainIsThePayloadAlone() {
        var structured = text(ExternalEventSender.messageFor(EVENT, "structured"));
        assertThat(structured).startsWith("{\"specversion\":\"1.0\"").contains("\"data\":{\"id\":\"B-1\"}")
                .contains("\"datacontenttype\":\"application/json\"");
        var plain = ExternalEventSender.messageFor(EVENT, "plain");
        assertThat(text(plain)).isEqualTo("{\"id\":\"B-1\"}");
        assertThat(plain.getHeaders()).doesNotContainKey("ce_id");
    }

    @Test
    void sendsToTheDestinationsTopicAndRefusalsThrow() {
        var destinations = new EventDestinationsProperties();
        var destination = new EventDestinationsProperties.Destination();
        destination.setTopic("booking-events");
        destination.setFormat("plain");
        destinations.getDestinations().put("bookings", destination);
        var bridge = mock(StreamBridge.class);
        when(bridge.send(eq("booking-events"), any(Object.class))).thenReturn(true);
        ExternalEventSender.send(bridge, destinations, EVENT);
        verify(bridge).send(eq("booking-events"), any(Object.class));
        assertThat(ExternalEventSender.formatOf(EVENT, destination)).isEqualTo("plain");

        when(bridge.send(eq("booking-events"), any(Object.class))).thenReturn(false);
        assertThatThrownBy(() -> ExternalEventSender.send(bridge, destinations, EVENT))
                .isInstanceOf(PartitionedEvents.EventPublicationRefusedException.class);
        assertThatThrownBy(() -> ExternalEventSender.send(bridge, new EventDestinationsProperties(), EVENT))
                .hasMessageContaining("no topic configured");
    }
}
