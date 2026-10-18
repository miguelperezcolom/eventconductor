package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-33 — asynchronous request/response in a single step, over Kafka. A PUBLISH_EVENT with an
 * {@code awaitReply} sends its request to a destination topic, and a responder answers the moment the
 * record arrives — with a CloudEvent on the reply topic (an {@code onEvent} reply) or a message on the
 * engine's {@code upstream} topic. The wait is committed with the request's outbox row, so the reply
 * finds the step waiting however fast it comes back, whichever of two pods consumes it.
 */
class Dist33AwaitReplyOverKafkaTest extends AbstractDistTest {

    private static final String REQUESTS = "dist33-request-events";
    private static final String MESSAGE_REQUESTS = "dist33-message-request-events";
    private static final String REPLIES = "dist33-reply-events";

    static ConfigurableApplicationContext podA;
    static ConfigurableApplicationContext podB;
    static Thread responder;
    static final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeAll
    static void startPods() {
        DistInfra.ensureStarted();
        DistInfra.createTopics(List.of(REQUESTS, MESSAGE_REQUESTS, REPLIES));
        Map<String, Object> config = Map.of(
                "workflow.events.destinations.dist33-requests.topic", REQUESTS,
                "workflow.events.destinations.dist33-msg-requests.topic", MESSAGE_REQUESTS,
                "workflow.events.destinations.dist33-replies.topic", REPLIES,
                "workflow.events.consumer.auto-offset-reset", "earliest",
                "workflow.events.consumer.group", "dist33");
        podA = DistInfra.startOrchestrator(config);
        podB = DistInfra.startOrchestrator(config);
        responder = new Thread(Dist33AwaitReplyOverKafkaTest::respond, "dist33-responder");
        responder.setDaemon(true);
        responder.start();
    }

    @AfterAll
    static void stopPods() throws InterruptedException {
        running.set(false);
        responder.join(5_000);
        podA.close();
        podB.close();
    }

    /** The fastest responder a topic allows: every request is answered as soon as it is polled. */
    private static void respond() {
        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dist33-responder-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (var consumer = new KafkaConsumer<byte[], byte[]>(consumerProps);
             var producer = new KafkaProducer<byte[], byte[]>(producerProps)) {
            consumer.subscribe(List.of(REQUESTS, MESSAGE_REQUESTS));
            while (running.get()) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    var requestId = new String(record.key(), StandardCharsets.UTF_8);
                    if (REQUESTS.equals(record.topic())) {
                        var reply = new ProducerRecord<>(REPLIES, record.key(),
                                ("{\"requestId\":\"" + requestId + "\",\"price\":\"42\"}").getBytes(StandardCharsets.UTF_8));
                        reply.headers().add("ce_specversion", bytes("1.0"));
                        reply.headers().add("ce_id", bytes("reply-" + requestId));
                        reply.headers().add("ce_source", bytes("dist33/pricing"));
                        reply.headers().add("ce_type", bytes("com.acme.quote.ready"));
                        reply.headers().add("contentType", bytes("application/json"));
                        producer.send(reply);
                        producer.flush();
                    } else {
                        DistInfra.publishUpstream(new MessageReceived("dist33-quote-ready", requestId,
                                List.of(new Variable("price", "42"))));
                    }
                }
            }
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aReplyEventProducedTheMomentTheRequestArrivesCompletesTheStep() {
        for (int i = 0; i < 5; i++) {
            createProcess("dist-await-reply-event", "dist33-e" + i);
        }
        for (int i = 0; i < 5; i++) {
            awaitProcessCompleted("dist33-e" + i);
            assertThat(stepStatuses("dist33-e" + i)).containsEntry("ask", "COMPLETED");
        }
    }

    @Test
    void aReplyMessageSentTheMomentTheRequestArrivesCompletesTheStep() {
        for (int i = 0; i < 5; i++) {
            createProcess("dist-await-reply-message", "dist33-m" + i);
        }
        for (int i = 0; i < 5; i++) {
            awaitProcessCompleted("dist33-m" + i);
            assertThat(stepStatuses("dist33-m" + i)).containsEntry("ask", "COMPLETED");
        }
    }
}
