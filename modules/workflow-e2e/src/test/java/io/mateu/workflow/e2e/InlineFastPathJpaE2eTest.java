package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.SyncInvocationHttp;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The synchronous fast path, proven by taking the relay away: with no outbox relay at all, nothing
 * but the inline drive of the pod that took the request can move the process — so a process that
 * reaches its REPLY here got there on the fast path alone. Every transition is still in the outbox;
 * none is left claimed afterwards.
 */
@TestPropertySource(properties = "workflow.outbox.relay-enabled=false")
class InlineFastPathJpaE2eTest extends AbstractJpaE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired SyncInvocationService service;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;

    private long claimedRows() {
        return outboxMessages().stream()
                .filter(row -> OutboxMessageStatus.InlineClaimed.name().equals(row.getStatus())).count();
    }

    @Test
    void theWholeProcessRunsInlineWithoutARelay() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-IN1")));
        var http = new SyncInvocationHttp(service, waiters, metrics);

        var result = http.invoke("sync-reply-end", "in-1", "wait=10",
                "{\"businessKey\": \"inline-1\", \"variables\": {\"bookingId\": \"B-IN1\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("reply").path("confirmation").asText())
                .isEqualTo("C-IN1");
        awaitStatus("inline-1", ProcessStatus.COMPLETED);
        assertThat(claimedRows()).as("nothing left claimed").isZero();
        assertThat(outboxMessages()).as("every transition was still written to the outbox").isNotEmpty();
    }

    @Test
    void anEarlyReplyIsInlineAndTheDriveStopsWhereTheWorkIsSomeoneElses() throws Exception {
        worker.on("notify", TestWorker.deferForever());
        var http = new SyncInvocationHttp(service, waiters, metrics);

        var result = http.invoke("sync-reply-early", "in-2", "wait=10",
                "{\"businessKey\": \"inline-2\", \"variables\": {\"bookingId\": \"B-IN2\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // The caller is answered as soon as the REPLY commits; the drive carries on behind it, up to
        // the worker, and stops there — the worker's answer is somebody else's to deliver.
        org.awaitility.Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(worker.invocationsOf("notify")).isEqualTo(1);
            assertThat(claimedRows()).isZero();
        });
        assertThat(process("inline-2").getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }
}
