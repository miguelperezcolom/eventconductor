package io.mateu.workflow.events.kafka;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.infra.in.events.InboundCloudEvents;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kafka mode: consumes the topics of the destinations that some definition's event trigger or
 * {@code onEvent} wait names, and hands each record — read as a CloudEvent by {@link InboundCloudEvents}
 * — to the {@link InboundEventService}.
 *
 * <p>One consumer group for the deployment ({@code workflow.events.consumer.group}), so each event is
 * used by one pod; the receipts make a redelivery after a rebalance harmless. The set of topics is
 * recomputed every {@code workflow.events.consumer.refresh-interval} and the listener container
 * restarted when it changes — a git import that adds a trigger is listened to without a restart. A new
 * group starts at {@code latest}: turning a trigger on does not replay a topic's history.
 *
 * <p>An event the engine could not hand over (a broker down under the starts or messages it produces)
 * is retried after {@code workflow.events.consumer.retry-backoff}, indefinitely: its offset is committed
 * only once it was handed over. A record that cannot be read is logged and skipped.
 *
 * <p>Lives in its own module, {@code inbound-events-kafka}, outside the engine: the engine matches
 * CloudEvents on destinations ({@link InboundEventService}) and knows nothing of how they arrive. An
 * application running the engine in kafka mode adds this module; {@code InboundEventKafkaAutoConfiguration}
 * wires it.
 */
@Slf4j
public class InboundEventKafkaConsumer {

    private final InboundEventService inboundEventService;
    private final EventTriggerService eventTriggerService;
    private final EventDestinationsProperties properties;
    private final Environment environment;

    private ScheduledExecutorService refresher;
    private ConcurrentMessageListenerContainer<byte[], byte[]> container;
    /** The topics the running container listens to; empty when none is running. */
    private Set<String> currentTopics = Set.of();
    /** Topic → the destinations configured with it (several may share one). */
    private volatile Map<String, List<String>> destinationsByTopic = Map.of();
    private Set<String> reportedUnconfigured = Set.of();

    public InboundEventKafkaConsumer(InboundEventService inboundEventService, EventTriggerService eventTriggerService,
                                     EventDestinationsProperties properties, Environment environment) {
        this.inboundEventService = inboundEventService;
        this.eventTriggerService = eventTriggerService;
        this.properties = properties;
        this.environment = environment;
    }

    /** After startup, once the definitions are loaded: the first look, then one every refresh interval. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getConsumer().isEnabled()) {
            log.info("Inbound domain events over Kafka are disabled (workflow.events.consumer.enabled=false)");
            return;
        }
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "inbound-event-topics");
            thread.setDaemon(true);
            return thread;
        });
        var interval = Math.max(1_000L, properties.getConsumer().getRefreshInterval().toMillis());
        refresher.scheduleWithFixedDelay(this::refreshQuietly, 0, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
        stopContainer();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Could not refresh the topics of inbound domain events; will retry", e);
        }
    }

    /** Recomputes which topics to listen to and restarts the container when they changed. */
    synchronized void refresh() {
        var byTopic = new LinkedHashMap<String, List<String>>();
        var unconfigured = new TreeSet<String>();
        for (var destination : eventTriggerService.listenedDestinations()) {
            var topic = eventTriggerService.topicOf(destination);
            if (topic == null) {
                unconfigured.add(destination);
            } else {
                byTopic.computeIfAbsent(topic, t -> new ArrayList<>()).add(destination);
            }
        }
        if (!unconfigured.equals(reportedUnconfigured)) {
            if (!unconfigured.isEmpty()) {
                log.warn("Destinations named by event triggers or onEvent waits have no topic configured, so"
                        + " nothing arrives on them over Kafka: {} (set workflow.events.destinations.<name>.topic)", unconfigured);
            }
            reportedUnconfigured = unconfigured;
        }
        destinationsByTopic = Map.copyOf(byTopic);
        var topics = new TreeSet<>(byTopic.keySet());
        if (topics.equals(currentTopics) && (container != null || topics.isEmpty())) {
            return;
        }
        stopContainer();
        currentTopics = Set.copyOf(topics);
        if (topics.isEmpty()) {
            return;
        }
        log.info("Listening for inbound domain events on {} (group {})", topics, properties.getConsumer().getGroup());
        var containerProperties = new ContainerProperties(topics.toArray(String[]::new));
        containerProperties.setMessageListener((MessageListener<byte[], byte[]>) this::onRecord);
        container = new ConcurrentMessageListenerContainer<>(new DefaultKafkaConsumerFactory<>(consumerConfig()),
                containerProperties);
        container.setConcurrency(Math.max(1, properties.getConsumer().getConcurrency()));
        container.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(properties.getConsumer().getRetryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS)));
        container.setBeanName("inboundEventContainer");
        container.start();
    }

    private void stopContainer() {
        if (container != null) {
            try {
                container.stop();
            } catch (RuntimeException e) {
                log.warn("Could not stop the inbound event listener cleanly", e);
            }
            container = null;
        }
        currentTopics = Set.of();
    }

    /** One record: read it, and receive it on every destination its topic is configured for. */
    void onRecord(ConsumerRecord<byte[], byte[]> record) {
        var destinations = destinationsByTopic.getOrDefault(record.topic(), List.of());
        var headers = new HashMap<String, String>();
        record.headers().forEach(header -> headers.put(header.key(),
                header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
        for (var destination : destinations) {
            io.mateu.workflow.domain.aggregates.InboundEvent event;
            try {
                event = InboundCloudEvents.fromRecord(destination, record.topic(), record.partition(), record.offset(),
                        record.key(), record.value(), headers);
                if (event.id() == null || event.source() == null) {
                    log.warn("Skipping a record on {}-{}@{}: a CloudEvent without id or source", record.topic(),
                            record.partition(), record.offset());
                    continue;
                }
            } catch (RuntimeException unreadable) {
                log.warn("Skipping an unreadable record on {}-{}@{}", record.topic(), record.partition(), record.offset(),
                        unreadable);
                continue;
            }
            // Thrown on: the error handler retries the record, and the receivers that already acted on
            // it are skipped by their receipts.
            inboundEventService.receive(event);
        }
    }

    private Map<String, Object> consumerConfig() {
        var config = new HashMap<String, Object>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getConsumer().getGroup());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, properties.getConsumer().getAutoOffsetReset());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return config;
    }

    /** The configured brokers: this consumer's own, else the binder's, else Spring Kafka's. */
    String bootstrapServers() {
        var own = properties.getConsumer().getBootstrapServers();
        if (own != null && !own.isBlank()) {
            return own;
        }
        return Objects.requireNonNullElse(environment.getProperty("spring.cloud.stream.kafka.binder.brokers",
                environment.getProperty("spring.kafka.bootstrap-servers")), "localhost:9092");
    }

    /** The topics listened to now (for tests and diagnostics). */
    synchronized Set<String> topics() {
        return currentTopics;
    }
}
