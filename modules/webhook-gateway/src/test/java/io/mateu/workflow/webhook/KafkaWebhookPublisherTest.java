package io.mateu.workflow.webhook;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Kafka transport's send: awaited, so the sender's 2xx means the broker has the event — and a send
 * the broker does not take, or does not take in time, is a 502 the sender can redeliver on.
 */
class KafkaWebhookPublisherTest {

    @SuppressWarnings("unchecked")
    final KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
    final WebhookDestinationsProperties destinations = new WebhookDestinationsProperties();

    KafkaWebhookPublisherTest() {
        var destination = new WebhookDestinationsProperties.Destination();
        destination.setTopic("github-topic");
        destinations.getDestinations().put("github-events", destination);
    }

    private KafkaWebhookPublisher publisher(Duration timeout) {
        return new KafkaWebhookPublisher(template, destinations, timeout);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEventIsSentOnTheDestinationsTopicAndAwaited() {
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher(Duration.ofSeconds(1)).publish("github-events", PublishersTest.EVENT);

        var sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("github-topic");
        assertThat(new String((byte[]) sent.getValue().key(), StandardCharsets.UTF_8)).isEqualTo("acme/shop");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendTheBrokerRefusesIsA502() {
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("not leader")));

        assertThatThrownBy(() -> publisher(Duration.ofSeconds(1)).publish("github-events", PublishersTest.EVENT))
                .isInstanceOfSatisfying(WebhookRejectedException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("github-topic");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendThatDoesNotCompleteInTimeIsA502() {
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher(Duration.ofMillis(50)).publish("github-events", PublishersTest.EVENT))
                .isInstanceOfSatisfying(WebhookRejectedException.class, e -> assertThat(e.status()).isEqualTo(502));
    }

    @Test
    void aBareEventIsARecordWithoutKeyOptionalHeadersOrValue() {
        var bare = new WebhookEvent("i", "s", "t", null, null, " ", null, null, null);

        var record = KafkaWebhookPublisher.record("topic", bare);

        assertThat(record.key()).isNull();
        assertThat(record.value()).isEmpty();
        assertThat(record.headers().lastHeader("ce_subject")).isNull();
        assertThat(record.headers().lastHeader("ce_time")).isNull();
        assertThat(record.headers().lastHeader("ce_datacontenttype")).isNull();
        assertThat(new String(record.headers().lastHeader("contentType").value(), StandardCharsets.UTF_8))
                .isEqualTo("application/json");
    }

    @Test
    void anAttributeWithoutValueIsAnEmptyHeader() {
        var event = new WebhookEvent("i", null, "t", null, null, null, null, "{}", Map.of());
        assertThat(KafkaWebhookPublisher.record("topic", event).headers().lastHeader("ce_source").value()).isEmpty();
    }

    @Test
    void aDestinationIsOnlyRoutedWhenItHasATopic() {
        var blank = new WebhookDestinationsProperties.Destination();
        blank.setTopic("  ");
        destinations.getDestinations().put("blank", blank);
        destinations.getDestinations().put("none", new WebhookDestinationsProperties.Destination());

        assertThat(destinations.topicOf("github-events")).isEqualTo("github-topic");
        assertThat(destinations.topicOf("blank")).isNull();
        assertThat(destinations.topicOf("none")).isNull();
        assertThat(destinations.topicOf("unknown")).isNull();
        assertThat(destinations.topicOf(null)).isNull();
    }
}
