package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-10 — Concurrent processes on a deliberately small connection pool.
 *
 * <p>Per-process exclusion used to be an advisory lock, which is session-scoped: acquiring it
 * took a connection out of the pool and held it for the whole critical section, while the work
 * inside needed a second one. Two connections per in-flight process made the pool size — not the
 * database — the ceiling on concurrency, and past it the failure was a wedge rather than a
 * slowdown: holders waiting for a connection to do the work they held the lock for.
 *
 * <p>Exclusivity is now a row lock held by the transaction the work already runs in, so one
 * connection covers both. This drives 40 concurrent processes through a 3-connection pool and
 * asserts they all finish.
 *
 * <p>Verified to discriminate: with the old two-connection shape restored, the pool is exhausted
 * ({@code total=3, active=3, waiting=2}) and processes stop completing — the test times out
 * rather than merely slowing down, which is the failure mode that matters. With the row lock it
 * passes in about 25 s.
 */
class Dist10SmallConnectionPoolTest extends AbstractDistTest {

    static final int POOL_SIZE = 3;
    static final int PROCESSES = 40;

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPod() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of(
                "spring.datasource.hikari.maximum-pool-size", String.valueOf(POOL_SIZE),
                // Fail fast rather than hang: if the engine still needed two connections per
                // in-flight process this must surface as an error, not as a stalled suite.
                "spring.datasource.hikari.connection-timeout", "5000"));
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void moreConcurrentProcessesThanPoolConnectionsAllComplete() {
        for (var i = 0; i < PROCESSES; i++) {
            createProcess("dist-sequential-3", "dist10-" + i);
        }

        for (var i = 0; i < PROCESSES; i++) {
            awaitProcessCompleted("dist10-" + i);
        }

        assertThat(PROCESSES).as("more processes in flight than the pool has connections")
                .isGreaterThan(POOL_SIZE);
    }
}
