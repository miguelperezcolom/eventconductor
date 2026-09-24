package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.SyncInvocationHttp;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A drive that spends its step budget stops and hands the rest back: with the relay off and a
 * budget of one message, the process makes one move inline and its remaining work is left
 * {@code Pending} — for the relay, which here is absent — rather than claimed and forgotten.
 */
@TestPropertySource(properties = {
        "workflow.outbox.relay-enabled=false",
        "workflow.sync.inline.max-steps=1"
})
class InlineBudgetJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired SyncInvocationService service;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;

    @Test
    void theRestGoesBackToThePendingOutbox() throws Exception {
        var http = new SyncInvocationHttp(service, waiters, metrics);

        var result = http.invoke("sync-reply-end", "budget-1", "wait=2",
                "{\"businessKey\": \"inline-budget-1\", \"variables\": {\"bookingId\": \"B-BU\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var statuses = outboxMessages().stream().map(row -> row.getStatus()).toList();
            assertThat(statuses).doesNotContain(OutboxMessageStatus.InlineClaimed.name());
            assertThat(statuses).contains(OutboxMessageStatus.Pending.name(), OutboxMessageStatus.Sent.name());
        });
    }
}
