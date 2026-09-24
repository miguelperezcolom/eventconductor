package io.mateu.workflow.e2e;

import io.mateu.e2ejpa.inlinecrash.InlineCrashTestApp;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The fast path survives the pod that was driving it: node A takes a synchronous invocation and
 * dies in the middle of its inline drive — inside an embedded worker, with the task's outbox row
 * claimed by A. Node B, on the same database, finds A's claim lapsed, hands the row back to its
 * relay, runs the task again (at-least-once, as on the normal path) and finishes the process; the
 * reply is there for the caller's retry. The same guarantee as {@code CrashRecoveryE2eTest}, with the
 * inline claim as the thing that must not strand work.
 */
class InlineCrashRecoveryE2eTest {

    private static final String DB_URL =
            "jdbc:h2:file:./target/inline-crash-db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL";

    private ConfigurableApplicationContext boot(boolean relayEnabled, String ddlAuto) {
        return new SpringApplicationBuilder(InlineCrashTestApp.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--workflow.mode=embedded",
                        "--workflow.persistence=jpa",
                        "--workflow.cron-enabled=false",
                        "--workflow.outbox.relay-enabled=" + relayEnabled,
                        "--workflow.outbox-poll-interval-ms=50",
                        "--workflow.timeout-scan-interval-ms=200",
                        "--workflow.sync.inline.claim-lease-ms=1000",
                        "--workflow.sync.inline.sweep-interval-ms=200",
                        "--spring.datasource.url=" + DB_URL,
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.jpa.hibernate.ddl-auto=" + ddlAuto,
                        "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false");
    }

    @Test
    void aPodThatDiesMidDriveLosesNothing() throws Exception {
        String invocationId;
        var inWorker = new CountDownLatch(1);
        var never = new CountDownLatch(1);

        // --- Node A: no relay, only the inline drive. It dies while the worker runs. ---
        try (var nodeA = boot(false, "create")) {
            nodeA.getBean(TestWorker.class).on("book", (request, callback, invocation) -> {
                inWorker.countDown();
                // The pod dies here, mid-task. A dead JVM runs no more code, so this thread neither
                // answers nor reacts to the shutdown's interrupt: it just never comes back.
                while (never.getCount() > 0) {
                    try {
                        never.await();
                    } catch (InterruptedException ignored) {
                        // still dead
                    }
                }
            });
            var started = nodeA.getBean(SyncInvocationService.class).start(new SyncInvocationService.StartRequest(
                    "sync-reply-end", "crash-key", "inline-crash-1", Map.of("bookingId", "B-CR"),
                    Duration.ZERO, null));
            invocationId = started.invocation().id();
            assertThat(inWorker.await(10, TimeUnit.SECONDS)).as("the drive reached the worker").isTrue();
            assertThat(nodeA.getBean(OutboxMessageEntityRepository.class)
                    .findByStatus(OutboxMessageStatus.InlineClaimed.name()))
                    .as("the task's row is claimed by the dying pod").isNotEmpty();
        }

        // --- Node B: relay on. A's claim lapses, the relay takes over, the process finishes. ---
        try (var nodeB = boot(true, "update")) {
            nodeB.getBean(TestWorker.class).on("book", TestWorker.succeed(TestWorker.var("confirmation", "C-CR")));
            var processes = nodeB.getBean(ProcessRepository.class);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(processes.findByBusinessKey("inline-crash-1").map(Process::getStatus))
                            .contains(ProcessStatus.COMPLETED));
            var service = nodeB.getBean(SyncInvocationService.class);
            var view = service.view(service.find(invocationId).orElseThrow());
            assertThat(view.reply().outcome()).isEqualTo(ProcessReply.Outcome.REPLIED);
            assertThat(view.reply().payload()).contains("C-CR");
            assertThat(nodeB.getBean(OutboxMessageEntityRepository.class)
                    .findByStatus(OutboxMessageStatus.InlineClaimed.name())).isEmpty();
        } finally {
            never.countDown(); // let node A's stranded thread go (it returns without answering)
        }
    }
}
