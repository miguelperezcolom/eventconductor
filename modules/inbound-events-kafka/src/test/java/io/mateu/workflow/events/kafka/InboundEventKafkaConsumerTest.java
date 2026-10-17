package io.mateu.workflow.events.kafka;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundEventKafkaConsumerTest {

    final InboundEventService inbound = mock(InboundEventService.class);
    final EventTriggerService triggers = mock(EventTriggerService.class);
    final EventDestinationsProperties properties = new EventDestinationsProperties();
    final MockEnvironment environment = new MockEnvironment();
    final InboundEventKafkaConsumer consumer = new InboundEventKafkaConsumer(inbound, triggers, properties, environment);

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aDestinationWithoutATopicIsNotListenedTo() {
        when(triggers.listenedDestinations()).thenReturn(Set.of("bookings"));
        when(triggers.topicOf("bookings")).thenReturn(null);

        consumer.refresh();

        assertThat(consumer.topics()).isEmpty();
    }

    @Test
    void aBinaryCloudEventIsReceivedOnEveryDestinationOfItsTopic() {
        when(triggers.listenedDestinations()).thenReturn(Set.of("bookings", "audit"));
        when(triggers.topicOf("bookings")).thenReturn(null);
        when(triggers.topicOf("audit")).thenReturn(null);
        consumer.refresh();   // nothing configured: no container, and no destinations for any topic

        var record = new ConsumerRecord<>("booking-events", 0, 7L, utf8("B-1"), utf8("{\"bookingId\":\"B-1\"}"));
        record.headers().add(new RecordHeader("ce_id", utf8("e-1")));
        record.headers().add(new RecordHeader("ce_source", utf8("pms")));
        record.headers().add(new RecordHeader("ce_type", utf8("com.acme.booking.created")));
        consumer.onRecord(record);
        verify(inbound, never()).receive(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsAreRoutedToTheDestinationsConfiguredForTheirTopic() throws Exception {
        // Two destinations sharing one topic: the record is received once on each.
        var field = InboundEventKafkaConsumer.class.getDeclaredField("destinationsByTopic");
        field.setAccessible(true);
        field.set(consumer, java.util.Map.of("booking-events", java.util.List.of("bookings", "audit")));

        var record = new ConsumerRecord<>("booking-events", 0, 7L, utf8("B-1"), utf8("{\"bookingId\":\"B-1\"}"));
        record.headers().add(new RecordHeader("ce_id", utf8("e-1")));
        record.headers().add(new RecordHeader("ce_source", utf8("pms")));
        record.headers().add(new RecordHeader("ce_type", utf8("com.acme.booking.created")));
        consumer.onRecord(record);

        var captor = ArgumentCaptor.forClass(InboundEvent.class);
        verify(inbound, times(2)).receive(captor.capture());
        assertThat(captor.getAllValues()).extracting(InboundEvent::destination).containsExactly("bookings", "audit");
        assertThat(captor.getAllValues().get(0).type()).isEqualTo("com.acme.booking.created");
        assertThat(captor.getAllValues().get(0).key()).isEqualTo("B-1");

        // A plain record gets an id and source from its position, so it is still received.
        consumer.onRecord(new ConsumerRecord<>("booking-events", 1, 3L, null, utf8("{}")));
        verify(inbound, times(4)).receive(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theBrokersAreTheConsumersOwnElseTheBindersElseSpringKafkas() {
        assertThat(consumer.bootstrapServers()).isEqualTo("localhost:9092");
        environment.setProperty("spring.kafka.bootstrap-servers", "k:1");
        assertThat(consumer.bootstrapServers()).isEqualTo("k:1");
        environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "b:2");
        assertThat(consumer.bootstrapServers()).isEqualTo("b:2");
        properties.getConsumer().setBootstrapServers("own:3");
        assertThat(consumer.bootstrapServers()).isEqualTo("own:3");
    }

    @Test
    void disabledItNeverStarts() {
        properties.getConsumer().setEnabled(false);
        consumer.start();
        consumer.stop();
        verify(triggers, never()).listenedDestinations();
    }
}
