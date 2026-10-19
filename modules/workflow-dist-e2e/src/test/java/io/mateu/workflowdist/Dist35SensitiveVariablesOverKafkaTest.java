package io.mateu.workflowdist;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-35 — sensitive variables over a real broker and PostgreSQL. The orchestrator has a key, the Kafka
 * worker has none: the task on the {@code work} topic carries the card number sealed (read raw off the
 * topic, as anyone with access to the broker would), the worker echoes the sealed value back, and the
 * engine opens it — the process completes with the plaintext, which no table holds.
 */
class Dist35SensitiveVariablesOverKafkaTest extends AbstractDistTest {

    static final String CARD = "4111111111111111";
    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() throws Exception {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of("workflow.encryption.keys.dist", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="));
        // Imported here, not under workflows/: other pods of this suite run without keys and would refuse it.
        var dir = Files.createTempDirectory("dist35");
        try (var in = Dist35SensitiveVariablesOverKafkaTest.class.getResourceAsStream("/dist35/dist-sensitive.json")) {
            Files.copy(in, dir.resolve("dist-sensitive.json"));
        }
        var result = orchestrator.getBean(ImportWorkflowDefinitionsFromDirectoryUseCase.class).handle(List.of(dir.toString()));
        assertThat(result.errors()).isEmpty();
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
        // Leave the shared database as other pods (which have no keys) expect it: an engine refuses to start
        // over a stored definition that declares sensitive variables without the keys to protect them.
        var jdbc = DistInfra.jdbc();
        jdbc.update("DELETE FROM workflow_definition_version WHERE definition_id = 'dist-sensitive'");
        jdbc.update("DELETE FROM workflow_definition_entity WHERE id = 'dist-sensitive'");
    }

    @Test
    void theTaskOnTheBrokerCarriesTheCardSealedAndTheProcessStillCompletes() {
        createProcess("dist-sensitive", "dist35-1", new Variable("cardNumber", CARD), new Variable("amount", "120"));
        awaitProcessCompleted("dist35-1");
        var processId = processId("dist35-1");

        // What the Kafka worker (no keys) received is what the broker carried: sealed.
        var task = WorkerStub.receivedFor(processId).getFirst();
        assertThat(task.variables()).anySatisfy(v -> {
            assertThat(v.name()).isEqualTo("cardNumber");
            assertThat(v.value()).startsWith("ec:enc:v1:dist:").doesNotContain(CARD);
        });
        assertThat(task.variables()).contains(new Variable("amount", "120"));

        // Read raw off the topic: the bytes never contain the card number.
        var records = rawRecords("work", processId);
        assertThat(records).isNotEmpty()
                .allSatisfy(record -> assertThat(record).doesNotContain(CARD))
                .anySatisfy(record -> assertThat(record).contains("ec:enc:v1:dist:"));

        // The engine opened what the worker echoed back: the process holds the plaintext, the tables do not.
        assertThat(orchestrator.getBean(ProcessRepository.class).findById(processId).orElseThrow().getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("cardNumber", CARD));
        var jdbc = DistInfra.jdbc();
        assertThat(jdbc.queryForObject("SELECT variables FROM process_entity WHERE id = ?", String.class, processId))
                .contains("ec:enc:v1:dist:").doesNotContain(CARD);
        assertThat(jdbc.queryForList("SELECT variables FROM step_execution_entity WHERE process_id = ?", String.class, processId))
                .allSatisfy(row -> assertThat(String.valueOf(row)).doesNotContain(CARD));
        assertThat(jdbc.queryForList("SELECT payload FROM outbox_message_entity WHERE partition_key = ?", String.class, processId))
                .isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain(CARD));
    }

    private static List<String> rawRecords(String topic, String processId) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dist35-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        var found = new ArrayList<String>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(processId)) {
                        found.add(record.value());
                    }
                }
            }
        }
        return found;
    }
}
