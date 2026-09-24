package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.PublishedEvents;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** PUBLISH_EVENT in embedded mode: one event, rendered from the process, delivered to the application's publisher. */
class PublishEventE2eTest extends AbstractE2eTest {

    @Autowired PublishedEvents published;

    @Test
    void theEventIsPublishedOnceWithItsRenderedPayloadAndKey() {
        worker.on("confirm", TestWorker.succeed());

        createProcess("publish-event", "pub-1", new Variable("bookingId", "B-7"), new Variable("nights", "2"));

        assertThat(process("pub-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        var events = published.forProcess(process("pub-1").getId());
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.destination()).isEqualTo("bookings");
        assertThat(event.eventType()).isEqualTo("com.acme.booking.confirmed");
        assertThat(event.key()).isEqualTo("B-7");
        assertThat(event.eventId()).isEqualTo(step("pub-1", "announce").id());
        assertThat(event.data()).isEqualTo("{\"bookingId\":\"B-7\",\"nights\":2,\"note\":\"Booking B-7 for pub-1\"}");
    }

    @Test
    void nothingIsPublishedForAStepThatNeverCompletes() {
        worker.on("confirm", TestWorker.fail());

        createProcess("publish-event", "pub-2", new Variable("bookingId", "B-8"));

        assertThat(process("pub-2").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(step("pub-2", "announce").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(published.forProcess(process("pub-2").getId())).isEmpty();
    }
}
