package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.PublishedEvents;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** PUBLISH_EVENT on the durable path: the event rides the outbox with the step's completion and is relayed once. */
class PublishEventJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired PublishedEvents published;

    @Test
    void theEventGoesThroughTheOutboxAndIsPublishedOnce() {
        worker.on("confirm", TestWorker.succeed());

        createProcess("publish-event", "pub-jpa-1", new Variable("bookingId", "B-9"), new Variable("nights", "1"));
        awaitStatus("pub-jpa-1", ProcessStatus.COMPLETED);

        var processId = process("pub-jpa-1").getId();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(published.forProcess(processId)).hasSize(1));
        assertThat(outboxMessages().stream()
                .filter(row -> row.getMessageType().endsWith("ExternalEventRequested")))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(OutboxMessageStatus.Sent.name()));
    }
}
