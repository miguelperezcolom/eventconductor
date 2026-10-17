package io.mateu.workflow.events.kafka;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.template.Templates;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Against an embedded broker: the consumer listens to the topics of the listened destinations, follows
 * the set as it changes, hands every record over, retries a failed hand-over and skips a record that
 * fails deterministically.
 */
class InboundEventKafkaConsumerBrokerTest {

    static EmbeddedKafkaKraftBroker broker;
    static KafkaProducer<byte[], byte[]> producer;

    final InboundEventService inbound = mock(InboundEventService.class);
    final EventTriggerService triggers = mock(EventTriggerService.class);
    final EventDestinationsProperties properties = new EventDestinationsProperties();
    final Set<String> listened = new LinkedHashSet<>();
    InboundEventKafkaConsumer consumer;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, "bookings-topic", "payments-topic");
        broker.afterPropertiesSet();
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    @AfterAll
    static void stopBroker() {
        producer.close();
        broker.destroy();
    }

    @BeforeEach
    void setUp() {
        properties.getConsumer().setBootstrapServers(broker.getBrokersAsString());
        properties.getConsumer().setGroup("inbound-it-" + UUID.randomUUID());
        properties.getConsumer().setAutoOffsetReset("earliest");
        properties.getConsumer().setRetryBackoff(Duration.ofMillis(50));
        when(triggers.listenedDestinations()).thenAnswer(inv -> Set.copyOf(listened));
        when(triggers.topicOf("bookings")).thenReturn("bookings-topic");
        when(triggers.topicOf("payments")).thenReturn("payments-topic");
        consumer = new InboundEventKafkaConsumer(inbound, triggers, properties, new MockEnvironment());
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void send(String topic, String id, String body) throws Exception {
        var record = new ProducerRecord<>(topic, utf8("k-" + id), utf8(body));
        record.headers().add(new RecordHeader("ce_id", utf8(id)));
        record.headers().add(new RecordHeader("ce_source", utf8("pms")));
        record.headers().add(new RecordHeader("ce_type", utf8("com.acme.event")));
        producer.send(record).get();
    }

    private static InboundEvent withId(String id) {
        return argThat(event -> event != null && id.equals(event.id()));
    }

    private Object container() throws Exception {
        var field = InboundEventKafkaConsumer.class.getDeclaredField("container");
        field.setAccessible(true);
        return field.get(consumer);
    }

    @Test
    void theTopicSetIsFollowedAndTheContainerRestartedOnlyWhenItChanges() throws Exception {
        consumer.refresh();
        assertThat(consumer.topics()).isEmpty();
        assertThat(container()).isNull();

        listened.add("bookings");
        consumer.refresh();
        assertThat(consumer.topics()).containsExactly("bookings-topic");
        var first = container();
        assertThat(first).isNotNull();

        // Unchanged: the running container is kept.
        consumer.refresh();
        assertThat(container()).isSameAs(first);

        // A topic added: a new container on both.
        listened.add("payments");
        consumer.refresh();
        assertThat(consumer.topics()).containsExactlyInAnyOrder("bookings-topic", "payments-topic");
        var second = container();
        assertThat(second).isNotSameAs(first);
        assertThat(((org.springframework.kafka.listener.MessageListenerContainer) first).isRunning()).isFalse();

        // A topic removed: restarted again.
        listened.remove("bookings");
        consumer.refresh();
        assertThat(consumer.topics()).containsExactly("payments-topic");
        assertThat(container()).isNotSameAs(second);

        // None left: stopped, nothing running.
        listened.clear();
        consumer.refresh();
        assertThat(consumer.topics()).isEmpty();
        assertThat(container()).isNull();
    }

    @Test
    void recordsOnAListenedTopicAreHandedOverToItsDestination() throws Exception {
        listened.add("bookings");
        consumer.refresh();
        send("bookings-topic", "route-1", "{\"bookingId\":\"B-1\"}");

        // The topic is shared by the tests and read from the earliest offset: pick this test's record.
        verify(inbound, timeout(20_000)).receive(withId("route-1"));
        var captor = ArgumentCaptor.forClass(InboundEvent.class);
        verify(inbound, org.mockito.Mockito.atLeastOnce()).receive(captor.capture());
        var event = captor.getAllValues().stream().filter(e -> "route-1".equals(e.id())).findFirst().orElseThrow();
        assertThat(event.destination()).isEqualTo("bookings");
        assertThat(event.source()).isEqualTo("pms");
        assertThat(event.type()).isEqualTo("com.acme.event");
        assertThat(event.key()).isEqualTo("k-route-1");
    }

    @Test
    void aFailedHandOverIsRetriedUntilItSucceeds() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(inv -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("broker down");
            }
            return null;
        }).when(inbound).receive(withId("retry-1"));
        listened.add("bookings");
        consumer.refresh();
        send("bookings-topic", "retry-1", "{}");

        verify(inbound, timeout(20_000).times(3)).receive(withId("retry-1"));
    }

    @Test
    void aDeterministicFailureIsSkippedAndTheNextRecordStillArrives() throws Exception {
        doAnswer(inv -> {
            throw new IllegalArgumentException("no source");
        }).when(inbound).receive(withId("bad-1"));
        doAnswer(inv -> {
            throw new Templates.TemplateException("cannot render", null);
        }).when(inbound).receive(withId("bad-2"));
        listened.add("bookings");
        consumer.refresh();
        send("bookings-topic", "bad-1", "{}");
        send("bookings-topic", "bad-2", "{}");
        send("bookings-topic", "good-1", "{}");

        verify(inbound, timeout(20_000)).receive(withId("good-1"));
        verify(inbound, times(1)).receive(withId("bad-1"));
        verify(inbound, times(1)).receive(withId("bad-2"));
    }

    @Test
    void startedItLooksAtOnceAndAFailedLookDoesNotStopTheRefresher() throws Exception {
        properties.getConsumer().setRefreshInterval(Duration.ofMillis(1));   // floored to one second
        var looks = new AtomicInteger();
        when(triggers.listenedDestinations()).thenAnswer(inv -> {
            if (looks.incrementAndGet() == 1) {
                throw new IllegalStateException("definitions not loaded yet");
            }
            return Set.of("bookings");
        });
        consumer.start();

        verify(triggers, timeout(10_000).atLeast(2)).listenedDestinations();
        send("bookings-topic", "started-1", "{}");
        verify(inbound, timeout(20_000)).receive(withId("started-1"));
        assertThat(consumer.topics()).containsExactly("bookings-topic");
    }

    @Test
    void stoppedTheContainerIsGone() throws Exception {
        listened.add("bookings");
        consumer.refresh();
        consumer.stop();
        assertThat(container()).isNull();
        assertThat(consumer.topics()).isEmpty();
        verify(inbound, times(0)).receive(any());
    }
}
