package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-12 — One event the engine cannot process must not stop the ones around it.
 *
 * <p>A worker reporting on a task the engine has never heard of — a stale id after a redeploy, a
 * misconfigured worker — makes the handler throw. Handled one event at a time that is logged and
 * dropped, and everything else carries on.
 *
 * <p>It stops being harmless the moment a whole poll batch shares a transaction: a single
 * unprocessable event marks that transaction rollback-only, so the good events committed beside
 * it are rolled back too, redelivered, and poisoned again. This drives real traffic with one such
 * event mixed into it and asserts the real traffic still finishes.
 *
 * <p>And that the poison itself is not merely dropped. Logging an event the engine cannot process
 * and moving on loses it silently; it is parked on the dead-letter topic instead, unchanged, so
 * somebody can see what arrived and replay it once they know why it failed.
 */
class Dist12PoisonEventTest extends AbstractDistTest {

    static final int PROCESSES = 12;

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
    void anUnprocessableEventDoesNotStallTheTrafficAroundIt() {
        for (var i = 0; i < PROCESSES; i++) {
            createProcess("dist-sequential-3", "dist12-" + i);
            if (i == PROCESSES / 2) {
                // A report for a step execution that does not exist: the use case looks it up and
                // throws. Published mid-burst so it shares a poll batch with real work.
                DistInfra.publishUpstream(new TaskStatusChanged(
                        "no-such-step-execution", TaskStatus.COMPLETED, List.of(), "no-such-process"));
            }
        }

        for (var i = 0; i < PROCESSES; i++) {
            awaitProcessCompleted("dist12-" + i);
        }

        await("the poison event reaches the dead-letter topic").atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deadLetters())
                        .as("an event the engine cannot process must be parked, not dropped")
                        .anySatisfy(deadLetter -> assertThat(deadLetter)
                                .contains("no-such-step-execution")));
    }

    private List<String> deadLetters() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dist12-inspector");
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
