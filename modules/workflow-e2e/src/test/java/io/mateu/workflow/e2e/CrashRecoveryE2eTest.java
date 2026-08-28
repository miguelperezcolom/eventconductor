package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.e2ejpa.crashrecovery.CrashRecoveryTestApp;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-02 (crash recovery), single-JVM variant. Demonstrates the durability guarantee that
 * separates EventConductor from a fire-and-forget engine: a process created on a node that
 * dies BEFORE its creation event was relayed is resumed and completed by a fresh node
 * reading the same database, purely from the persisted outbox.
 *
 * <p>Two Spring contexts share one file-based H2 database:
 * <ol>
 *   <li>Node A boots with the outbox relay DISABLED, creates a process (its {@code ProcessCreated}
 *       event lands in the outbox as Pending and nothing drives it forward), then "crashes"
 *       (context closed).</li>
 *   <li>Node B boots against the same database with the relay ENABLED; it drains the pending
 *       outbox and drives the process to completion — no work is lost.</li>
 * </ol>
 */
class CrashRecoveryE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String DB_URL =
            "jdbc:h2:file:./target/crash-recovery-db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL";

    private ConfigurableApplicationContext boot(boolean relayEnabled, String ddlAuto) {
        // Pass config as command-line args (high precedence) so they override the module's
        // application.properties, which pins memory mode for the synchronous suite.
        return new SpringApplicationBuilder(CrashRecoveryTestApp.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--workflow.mode=embedded",
                        "--workflow.persistence=jpa",
                        "--workflow.cron-enabled=false",
                        "--workflow.outbox.relay-enabled=" + relayEnabled,
                        "--workflow.outbox-poll-interval-ms=50",
                        "--workflow.timeout-scan-interval-ms=200",
                        "--spring.datasource.url=" + DB_URL,
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.jpa.hibernate.ddl-auto=" + ddlAuto,
                        "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false");
    }

    @Test
    void processSurvivesNodeCrashAndResumesOnAnotherNode() {
        String businessKey = "crash-1";

        // --- Node A: relay off. Create a process; it must NOT progress. ---
        try (var nodeA = boot(false, "create")) {
            nodeA.getBean(ProcessUpstreamEventUseCase.class).handle(new ProcessUpstreamEventCommand(
                    new ProcessCreationRequested("sequential-3", businessKey, List.of())));

            var processes = nodeA.getBean(ProcessRepository.class);
            var outbox = nodeA.getBean(OutboxMessageEntityRepository.class);

            // The process exists but is stuck: no relay means the creation event is never
            // dispatched, so no step runs. This models a node that persisted state and then
            // crashed before the outbox was relayed.
            assertThat(processes.findByBusinessKey(businessKey)).isPresent();
            assertThat(processes.findByBusinessKey(businessKey).map(Process::getStatus))
                    .isNotEqualTo(java.util.Optional.of(ProcessStatus.COMPLETED));
            assertThat(outbox.findByStatus(OutboxMessageStatus.Pending.name()))
                    .as("the creation event is durably parked in the outbox, awaiting relay")
                    .isNotEmpty();
        }

        // --- Node B: relay on, same database. It must resume and finish the process. ---
        try (var nodeB = boot(true, "update")) {
            var processes = nodeB.getBean(ProcessRepository.class);
            await().atMost(TIMEOUT).untilAsserted(() ->
                    assertThat(processes.findByBusinessKey(businessKey).map(Process::getStatus))
                            .as("a fresh node resumes the process from the persisted outbox")
                            .contains(ProcessStatus.COMPLETED));
        }
    }
}
