package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-14 — A record the engine cannot read must not disappear without a word.
 *
 * <p>{@link Dist12PoisonEventTest} covers the event that parses and cannot be handled. This covers
 * the one before it: bytes that never become an event at all. It is a different code path and it
 * used to have a different, worse outcome — the message converter failed, the binder skipped the
 * record, the batch committed, and the offset advanced. No log line at any level, no dead letter,
 * no metric, lag back to zero.
 *
 * <p>That combination is undiagnosable from outside, which is the point of this test. A healthy
 * engine that created nothing reads exactly like a producer that never sent anything, so the search
 * starts at the producer, then the topic, then the consumer group, and the message content is the
 * last thing anyone looks at.
 *
 * <p>The payload here is the shape that produced the report: valid JSON to a glance, with one string
 * value carrying an invalid escape. Nothing rejects it on the way out — the producer is writing
 * text — and it fails on the way in.
 */
class Dist14UnreadableRecordTest extends AbstractDistTest {

    /**
     * Valid at the top level: the braces balance and a naive reader sees an object. {@code \}} is not
     * an escape any JSON parser accepts, so the value never closes and the record never parses.
     */
    static final String UNREADABLE = """
            {"type":"process-creation-requested","workflowDefinitionId":"dist-sequential-3",\
            "businessKey":"dist14-unreadable","variables":[{"name":"TEST_CONFIG","value":"{\\}"}]}""";

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPod() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void anUnreadableRecordIsParkedRatherThanDroppedInSilence() {
        DistInfra.publishRawUpstream(UNREADABLE);

        await("the unreadable record reaches the dead-letter topic").atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(deadLetters())
                        .as("bytes the engine cannot read must be parked, not skipped in silence")
                        .anySatisfy(deadLetter -> assertThat(deadLetter)
                                .contains("dist14-unreadable")));
    }

    @Test
    void theRecordsAroundItAreStillProcessed() {
        createProcess("dist-sequential-3", "dist14-before");
        DistInfra.publishRawUpstream(UNREADABLE);
        createProcess("dist-sequential-3", "dist14-after");

        // Skipping is the right call for a record that will never parse — failing the batch would
        // redeliver it for ever. What must not happen is the skip taking the batch down with it.
        awaitProcessCompleted("dist14-before");
        awaitProcessCompleted("dist14-after");
    }

    /**
     * Read as bytes and compared as text, because the payload parked here is not JSON — that is the
     * whole reason it is here — so anything that tries to deserialise it would fail the same way the
     * engine did.
     */
    private List<String> deadLetters() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dist14-inspector");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var found = new ArrayList<String>();
        try (var consumer = new KafkaConsumer<byte[], byte[]>(props)) {
            var partitions = consumer.partitionsFor("dead-letter");
            if (partitions == null || partitions.isEmpty()) {
                return found;
            }
            var assignment = new ArrayList<TopicPartition>();
            partitions.forEach(info -> assignment.add(new TopicPartition(info.topic(), info.partition())));
            consumer.assign(assignment);
            consumer.seekToBeginning(assignment);
            for (var attempt = 0; attempt < 3; attempt++) {
                consumer.poll(Duration.ofSeconds(1)).forEach(record ->
                        found.add(new String(record.value(), StandardCharsets.UTF_8)));
            }
        }
        return found;
    }
}
