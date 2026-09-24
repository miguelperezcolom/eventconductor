package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-29 — PUBLISH_EVENT over Kafka. The event reaches its destination's topic as a CloudEvent in
 * binary content mode (ce_* headers, the payload as the value), keyed by the rendered key; one event
 * per completed step; and events of steps completed while the broker is down are delivered once it
 * returns — the outbox guarantee, extended to the outside world.
 */
class Dist29PublishEventOverKafkaTest extends AbstractDistTest {

    private static final String TOPIC = "orders-events";

    static ConfigurableApplicationContext pod;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureStarted();
        DistInfra.createTopics(List.of(TOPIC));
        pod = DistInfra.startOrchestrator(Map.of("workflow.events.destinations.orders.topic", TOPIC));
    }

    @AfterAll
    static void stopPods() {
        pod.close();
    }

    @AfterEach
    void kafkaBack() {
        DistInfra.resumeKafka();
    }

    private static KafkaConsumer<byte[], byte[]> consumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dist29-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        var consumer = new KafkaConsumer<byte[], byte[]>(props);
        consumer.subscribe(List.of(TOPIC));
        return consumer;
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static List<ConsumerRecord<byte[], byte[]>> read(KafkaConsumer<byte[], byte[]> consumer, String prefix, int atLeast) {
        var records = new ArrayList<ConsumerRecord<byte[], byte[]>>();
        await().atMost(Duration.ofSeconds(120)).until(() -> {
            consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                if (new String(record.key(), StandardCharsets.UTF_8).startsWith(prefix)) {
                    records.add(record);
                }
            });
            return records.size() >= atLeast;
        });
        return records;
    }

    @Test
    void theEventArrivesAsACloudEventKeyedByTheRenderedKey() {
        try (var consumer = consumer()) {
            createProcess("dist-publish-event", "dist29-a", new Variable("orderId", "O-29A"), new Variable("amount", "12"));
            awaitProcessCompleted("dist29-a");

            var record = read(consumer, "O-29A", 1).getFirst();
            assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("O-29A");
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":\"O-29A\",\"amount\":12}");
            assertThat(header(record, "ce_specversion")).isEqualTo("1.0");
            assertThat(header(record, "ce_type")).isEqualTo("com.acme.order.placed");
            assertThat(header(record, "ce_subject")).isEqualTo("dist29-a");
            assertThat(header(record, "ce_processid")).isEqualTo(processId("dist29-a"));
        }
    }

    @Test
    void eventsOfStepsCompletedDuringAnOutageAreDeliveredOnceTheBrokerReturns() throws Exception {
        try (var consumer = consumer()) {
            consumer.poll(Duration.ofMillis(500));
            DistInfra.pauseKafka();
            // Created straight into the database on the pod (creation needs no broker): their events
            // cannot go anywhere while it is down.
            var create = pod.getBean(io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase.class);
            for (int i = 0; i < 5; i++) {
                create.handle(new io.mateu.workflow.application.usecases.process.create.CreateProcessCommand(
                        UUID.randomUUID().toString(), "dist-publish-event", "dist29-o" + i,
                        List.of(new io.mateu.workflow.domain.aggregates.Variable("orderId", "O-29O" + i),
                                new io.mateu.workflow.domain.aggregates.Variable("amount", "1")), null));
            }
            Thread.sleep(3_000);
            DistInfra.resumeKafka();
            for (int i = 0; i < 5; i++) {
                awaitProcessCompleted("dist29-o" + i);
            }
            var records = read(consumer, "O-29O", 5);
            var ids = new HashSet<String>();
            records.forEach(record -> ids.add(header(record, "ce_id")));
            assertThat(ids).as("one event per completed step, each with a stable id").hasSize(5);
            await().atMost(Duration.ofSeconds(60)).until(() -> pendingOutboxMessages() == 0);
        }
    }
}
