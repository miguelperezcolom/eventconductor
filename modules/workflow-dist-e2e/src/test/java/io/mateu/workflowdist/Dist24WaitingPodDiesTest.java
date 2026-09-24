package io.mateu.workflowdist;

import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.SyncCalls;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-24 — the pod holding the caller's connection dies. The caller loses its connection (here: its
 * wait ends empty when the pod shuts down); the process carries on on the surviving pod; the caller
 * retries with the same idempotency key and gets the one reply — no second process.
 */
class Dist24WaitingPodDiesTest extends AbstractDistTest {

    static ConfigurableApplicationContext survivor;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        survivor = DistInfra.startOrchestrator(Map.of("workflow.sync.max-deadline-ms", "60000"));
    }

    @AfterAll
    static void stopPods() {
        survivor.close();
    }

    @Test
    void theRetryByKeyFindsTheReplyOnTheSurvivingPod() {
        var held = new AtomicReference<TaskExecutionRequested>();
        WorkerStub.on("dist-sync-workers", "s2", (request, invocation) -> held.set(request));
        var doomed = DistInfra.startOrchestrator(Map.of("workflow.sync.max-deadline-ms", "60000"));

        var started = SyncCalls.start(doomed, "dist-sync-workers", "dist24-key", Map.of("orderId", "O-24"),
                Duration.ofSeconds(30));
        var waiting = doomed.getBean(io.mateu.workflow.application.sync.SyncInvocationService.class)
                .await(started.invocation(), started.timeToWait());
        await().atMost(Duration.ofSeconds(60)).until(() -> held.get() != null);

        doomed.close(); // the pod dies with the caller waiting on it
        assertThat(waiting.join().replied()).as("the caller's connection went with the pod").isFalse();

        WorkerStub.complete(held.get());
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() ->
                assertThat(SyncCalls.byKey(survivor, "dist-sync-workers", "dist24-key").replied()).isTrue());

        var retry = SyncCalls.invoke(survivor, "dist-sync-workers", "dist24-key", Map.of("orderId", "O-24"),
                Duration.ofSeconds(5));
        assertThat(retry.invocation().processId()).isEqualTo(started.invocation().processId());
        assertThat(retry.reply().outcome()).isEqualTo(ProcessReply.Outcome.REPLIED);
        assertThat(retry.reply().payload()).contains("O-24");
        assertThat(WorkerStub.executionCount(started.invocation().processId(), "s1")).isEqualTo(1);
    }
}
