package io.mateu.workflowdist;

import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.dtos.events.integration.ProcessUpdateRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-36 — process updates over Kafka. The door records the update and sends {@code process-update-requested}
 * to the {@code upstream} topic; the pod that owns the process's partition applies it and records the outcome,
 * and the caller — on whichever pod took the request — is woken by the poll. Concurrent updates from two pods
 * are serialized by partition ownership: none is lost. A command produced straight to the topic is a door of
 * its own.
 */
class Dist36ProcessUpdatesOverKafkaTest extends AbstractDistTest {

    static ConfigurableApplicationContext podA;
    static ConfigurableApplicationContext podB;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureStarted();
        podA = DistInfra.startOrchestrator(Map.of());
        podB = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        podA.close();
        podB.close();
    }

    private String waitingProcess(String businessKey) {
        createProcess("dist-updates", businessKey);
        Awaitility.await("process " + businessKey + " waits").atMost(DEFAULT_TIMEOUT).pollInterval(Duration.ofMillis(250))
                .until(() -> "PENDING".equals(stepStatuses(businessKey).get("wait")) && processStatus(businessKey)
                        .filter("RUNNING"::equals).isPresent());
        return processId(businessKey);
    }

    private static CompletableFuture<ProcessUpdate> update(ConfigurableApplicationContext pod, String processId,
                                                           String name, String payload) {
        var service = pod.getBean(ProcessUpdateService.class);
        var submitted = service.submit(new ProcessUpdateService.UpdateRequest(processId, null, name, payload, null,
                Duration.ofSeconds(30), null));
        return service.await(submitted.update(), submitted.timeToWait());
    }

    @Test
    void concurrentUpdatesFromTwoPodsAreAllAppliedByTheOwner() {
        var processId = waitingProcess("dist36-concurrent");

        var futures = new ArrayList<CompletableFuture<ProcessUpdate>>();
        for (int i = 0; i < 10; i++) {
            futures.add(update(i % 2 == 0 ? podA : podB, processId, "addNote", "{\"note\": \"n" + i + "\"}"));
        }
        for (var future : futures) {
            assertThat(future.join().status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        }

        var notes = DistInfra.jdbc().queryForObject("SELECT variables FROM process_entity WHERE id = ?", String.class, processId);
        var seen = new HashSet<String>();
        for (int i = 0; i < 10; i++) {
            if (notes.contains("n" + i)) {
                seen.add("n" + i);
            }
        }
        assertThat(seen).hasSize(10);

        var released = update(podB, processId, "release", null).join();
        assertThat(released.result()).isEqualTo("\"released\"");
        awaitProcessCompleted("dist36-concurrent");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> update(podA, processId, "release", null))
                .isInstanceOf(io.mateu.workflow.application.updates.ProcessUpdateRejectedException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void aCommandProducedStraightToTheTopicIsRecordedAndApplied() {
        var processId = waitingProcess("dist36-topic");

        DistInfra.publishUpstream(new ProcessUpdateRequested("dist36-cmd-1", processId, "addNote",
                "{\"note\": \"from-kafka\"}", "integration"));

        Awaitility.await("the owner records the update").atMost(DEFAULT_TIMEOUT).pollInterval(Duration.ofMillis(250))
                .until(() -> DistInfra.jdbc().queryForList("SELECT status FROM process_update WHERE id = 'dist36-cmd-1'",
                        String.class).contains("COMPLETED"));
        assertThat(DistInfra.jdbc().queryForObject("SELECT requested_by FROM process_update WHERE id = 'dist36-cmd-1'",
                String.class)).isEqualTo("integration");
        assertThat(DistInfra.jdbc().queryForObject("SELECT variables FROM process_entity WHERE id = ?", String.class,
                processId)).contains("from-kafka");
        assertThat(podA.getBean(io.mateu.workflow.application.out.ProcessUpdateRepository.class)
                .findByProcessId(processId)).extracting(ProcessUpdate::id).containsExactly("dist36-cmd-1");
    }
}
