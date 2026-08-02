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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-11 — Every event of a process carries that process as its Kafka key.
 *
 * <p>This is the foundation ownership rests on. A keyed event hashes to a fixed partition, and a
 * consumer group gives each partition to exactly one pod, so per-process serialization — and
 * per-process <em>ordering</em>, which an unkeyed topic never provided — become properties of how
 * events are addressed rather than something the engine arranges with locks afterwards.
 *
 * <p>Reads the topics directly rather than trusting that a key was set: the binder's default key
 * serializer is not a given, and a key that silently fails to be written would leave every event
 * round-robining across partitions exactly as before, with nothing else looking different.
 */
class Dist11PartitionOwnershipTest extends AbstractDistTest {

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
    void allEventsOfAProcessShareOnePartition() {
        // Only this test's own traffic: running after the rest of the suite there is far too
        // much history to drain, and a reader that never reaches the end would report no keys
        // at all — a pass condition that means nothing.
        var from = offsets("outbox", "upstream");

        createProcess("dist-sequential-3", "dist11-1");
        awaitProcessCompleted("dist11-1");
        var processId = processId("dist11-1");

        var partitionsByKey = new HashMap<String, HashSet<Integer>>();
        var keyed = 0;
        for (var record : readFrom(from)) {
            if (record.key == null) {
                continue;
            }
            keyed++;
            partitionsByKey.computeIfAbsent(record.key, k -> new HashSet<>()).add(record.partition);
        }

        assertThat(keyed).as("events must be keyed at all — an unset key looks like success")
                .isPositive();
        assertThat(partitionsByKey).as("this process must appear as a key").containsKey(processId);
        assertThat(partitionsByKey.get(processId))
                .as("every event of a process must land on one partition, or no single pod owns it")
                .hasSize(1);
        assertThat(partitionsByKey.values()).allSatisfy(partitions ->
                assertThat(partitions).as("no key may span partitions").hasSize(1));
    }

    private record Record(String key, int partition) {}

    private Map<TopicPartition, Long> offsets(String... topics) {
        try (var consumer = inspector()) {
            var assignment = new ArrayList<TopicPartition>();
            for (var topic : topics) {
                consumer.partitionsFor(topic).forEach(info ->
                        assignment.add(new TopicPartition(info.topic(), info.partition())));
            }
            return new HashMap<>(consumer.endOffsets(assignment));
        }
    }

    private java.util.List<Record> readFrom(Map<TopicPartition, Long> from) {
        var records = new ArrayList<Record>();
        try (var consumer = inspector()) {
            var assignment = new ArrayList<>(from.keySet());
            consumer.assign(assignment);
            from.forEach(consumer::seek);
            var end = consumer.endOffsets(assignment);
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && !drained(consumer, assignment, end)) {
                consumer.poll(Duration.ofSeconds(1)).forEach(record -> records.add(new Record(
                        record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                        record.partition())));
            }
        }
        return records;
    }

    private boolean drained(KafkaConsumer<byte[], byte[]> consumer,
                            java.util.List<TopicPartition> assignment, Map<TopicPartition, Long> end) {
        return assignment.stream().allMatch(partition -> consumer.position(partition) >= end.get(partition));
    }

    private KafkaConsumer<byte[], byte[]> inspector() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dist11-inspector");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
