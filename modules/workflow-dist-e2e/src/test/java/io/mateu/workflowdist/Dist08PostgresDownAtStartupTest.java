package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-08 — Orchestrator started while PostgreSQL is unavailable, database comes up later.
 * The pod must boot promptly without a database (no schema work, no metadata probe, lazy
 * pool) and, once PostgreSQL is reachable, resume the work that was already committed:
 * a process left mid-flight with its next event parked as {@code Pending} in the outbox
 * (the DIST-02 crash window) must be driven to completion by the new pod's relay, and
 * brand-new processes must run end to end.
 *
 * <p>Requires the DB-resilient settings mirrored in {@link #RESILIENT_DB}: without them the
 * context either fails (lock-dialect probe, schema update) or blocks on the missing database.
 */
class Dist08PostgresDownAtStartupTest extends AbstractDistTest {

    /**
     * Boot without touching the database: lazy pool (no init probe, bounded waits), no
     * ddl-auto schema work (the schema pre-exists), and dialect fixed so Hibernate skips the
     * JDBC metadata probe. Candidate production recipe for DB-less startup (with Flyway the
     * equivalent is {@code spring.flyway.connect-retries}).
     */
    static final Map<String, Object> RESILIENT_DB = Map.of(
            "spring.datasource.hikari.initialization-fail-timeout", "-1",
            "spring.datasource.hikari.connection-timeout", "2000",
            "spring.jpa.hibernate.ddl-auto", "none",
            "spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect",
            "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", "false");

    ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void worker() {
        DistInfra.ensureWorkerStarted();
    }

    @AfterEach
    void cleanup() {
        DistInfra.resumePostgres();
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void orchestratorBootsWithPostgresPausedThenResumesPendingWorkWhenItComesBack() throws Exception {
        // ── Phase 0 (database up): first pod creates the schema, imports the definitions and
        // leaves a process mid-flight with its next event committed as Pending in the outbox —
        // exactly the durable state of the DIST-02 crash window. ──
        orchestrator = DistInfra.startOrchestrator(Map.of());

        var s1Request = new AtomicReference<TaskExecutionRequested>();
        WorkerStub.on("dist-crash-recovery", "s1", (request, invocation) -> s1Request.set(request));

        createProcess("dist-crash-recovery", "dist08-1");
        await("worker received s1").atMost(DEFAULT_TIMEOUT).until(() -> s1Request.get() != null);
        var processId = processId("dist08-1");

        var relayLock = DistInfra.blockOutboxRelay();
        try {
            WorkerStub.complete(s1Request.get());
            await("s1 committed as COMPLETED").atMost(DEFAULT_TIMEOUT)
                    .until(() -> "COMPLETED".equals(stepStatuses("dist08-1").get("s1")));
            await("undispatched event parked in the outbox").atMost(DEFAULT_TIMEOUT)
                    .until(() -> pendingOutboxMessages() > 0);
            orchestrator.close();
            orchestrator = null;
            // Assert while the gate is still held. Every pod relays now and drains until empty,
            // so releasing first leaves a window in which the parked message is gone before the
            // assertion reads it — the state under test is "parked", not "parked a moment ago".
            assertThat(pendingOutboxMessages()).isGreaterThan(0);
        } finally {
            DistInfra.unblockOutboxRelay(relayLock);
        }

        // ── Phase 1: PostgreSQL down, a fresh pod boots anyway. ──
        DistInfra.pausePostgres();

        long t0 = System.currentTimeMillis();
        orchestrator = DistInfra.startOrchestrator(RESILIENT_DB);
        long bootMs = System.currentTimeMillis() - t0;
        System.out.println("[chaos] orchestrator booted in " + bootMs + "ms with PostgreSQL paused");
        assertThat(bootMs).as("boot must not block on the missing database").isLessThan(60_000);

        // ── Phase 2: the database comes up; the pod must resume the parked process… ──
        DistInfra.resumePostgres();

        awaitProcessCompleted("dist08-1");
        assertThat(stepStatuses("dist08-1"))
                .containsEntry("s1", "COMPLETED")
                .containsEntry("s2", "COMPLETED")
                .containsEntry("end", "COMPLETED");
        assertThat(WorkerStub.executionCount(processId, "s1")).isEqualTo(1);
        assertThat(WorkerStub.executionCount(processId, "s2")).isEqualTo(1);

        // ── …and handle brand-new work end to end. ──
        createProcess("dist-sequential-3", "dist08-2");
        awaitProcessCompleted("dist08-2");
        await("outbox drained").atMost(DEFAULT_TIMEOUT).until(() -> pendingOutboxMessages() == 0);
    }
}
