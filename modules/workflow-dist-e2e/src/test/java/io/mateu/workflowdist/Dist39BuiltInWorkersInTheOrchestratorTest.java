package io.mateu.workflowdist;

import com.sun.net.httpserver.HttpServer;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-39 — the orchestrator serves its built-in tasks itself. No worker deployment runs here: the
 * orchestrator's own image consumes {@code http-calls} and {@code event-publications}, so an HTTP_CALL
 * and a PUBLISH_EVENT run end to end over Kafka with nothing but the orchestrator, the broker and the
 * database — each still dispatched through the outbox and answered on {@code upstream}, like any task.
 */
class Dist39BuiltInWorkersInTheOrchestratorTest extends AbstractDistTest {

    private static final String TOPIC = "priced-events";

    static ConfigurableApplicationContext pod;
    static HttpServer pricing;
    static final AtomicInteger quotes = new AtomicInteger();

    @BeforeAll
    static void startPods() throws Exception {
        pricing = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pricing.createContext("/quotes/", exchange -> {
            quotes.incrementAndGet();
            var bytes = "{\"price\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        pricing.start();
        DistInfra.ensureStarted();
        DistInfra.createTopics(List.of(TOPIC));
        pod = DistInfra.startOrchestrator(Map.of(
                "workflow.events.destinations.priced.topic", TOPIC,
                "workflow.http.connections.pricing.base-url", "http://127.0.0.1:" + pricing.getAddress().getPort()));
    }

    @AfterAll
    static void stopPods() {
        pod.close();
        pricing.stop(0);
    }

    @Test
    void anHttpCallAndAPublishedEventRunWithNoWorkerDeployment() {
        try (var consumer = consumer()) {
            createProcess("dist-builtin-workers", "dist39-a", new Variable("orderId", "O-39A"));
            awaitProcessCompleted("dist39-a");

            assertThat(quotes.get()).isEqualTo(1);
            var record = read(consumer, "O-39A");
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":\"O-39A\",\"price\":42}");
            assertThat(header(record, "ce_type")).isEqualTo("com.acme.order.priced");
            assertThat(header(record, "ce_processid")).isEqualTo(processId("dist39-a"));
        }
    }

    private static KafkaConsumer<byte[], byte[]> consumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dist39-" + UUID.randomUUID());
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

    private static ConsumerRecord<byte[], byte[]> read(KafkaConsumer<byte[], byte[]> consumer, String key) {
        var records = new ArrayList<ConsumerRecord<byte[], byte[]>>();
        await().atMost(Duration.ofSeconds(120)).until(() -> {
            consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                if (key.equals(new String(record.key(), StandardCharsets.UTF_8))) {
                    records.add(record);
                }
            });
            return !records.isEmpty();
        });
        return records.getFirst();
    }
}
