package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-EMB-01 — one blocked worker must not stop the engine.
 *
 * <p>In JPA persistence a single thread drains the outbox, and dispatching a task used to mean
 * running the worker on it. A worker that blocked — an HTTP call to a service that accepted the
 * connection and never answered — stopped every process in the JVM, not just its own: no
 * step-over ran, and processes created afterwards sat with every step in {@code CREATED}, which
 * reads as "waiting for its preconditions" and is indistinguishable from a workflow whose
 * preconditions genuinely never came.
 *
 * <p>With {@code workflow.embedded.worker-threads} the task goes to a pool and the relay stays
 * free. This holds one worker hostage for the length of the test and requires an unrelated
 * process to run to completion meanwhile — which, before the pool, timed out here.
 */
@TestPropertySource(properties = "workflow.embedded.worker-threads=4")
class EmbeddedWorkerPoolE2eTest extends AbstractJpaE2eTest {

    @Test
    void aBlockedWorkerDoesNotStopUnrelatedProcesses() throws Exception {
        var release = new CountDownLatch(1);
        var blockedWorkerIsIn = new CountDownLatch(1);
        var hostage = new AtomicReference<String>();

        // The first process to reach s1 is held there; everything else runs normally. Claiming by
        // processId rather than by invocation count keeps it deterministic now that two tasks can
        // genuinely be in the worker at once.
        worker.on("s1", (request, callback, invocation) -> {
            if (hostage.compareAndSet(null, request.processId())) {
                blockedWorkerIsIn.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            callback.complete();
        });

        try {
            createProcess("sequential-3", "held");
            assertThat(blockedWorkerIsIn.await(15, TimeUnit.SECONDS))
                    .as("the worker of the first process is inside s1 and stuck")
                    .isTrue();

            createProcess("sequential-3", "unrelated");

            awaitStatus("unrelated", ProcessStatus.COMPLETED);
        } finally {
            release.countDown();
        }

        // And the hostage was not lost along the way: it finishes once its worker returns.
        awaitStatus("held", ProcessStatus.COMPLETED);
    }
}
