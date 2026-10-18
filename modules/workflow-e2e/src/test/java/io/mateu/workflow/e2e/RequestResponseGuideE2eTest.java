package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The two definitions of guides/request-response.md, run as written: {@code booking-quote} asks
 * with a PUBLISH_EVENT and waits for the answer in the same step ({@code awaitReply} with an
 * {@code onEvent}); {@code quote-service} is started by that event (a trigger) and answers with a
 * PUBLISH_EVENT carrying the correlation id. In memory mode with loopback the whole exchange runs on
 * one thread, inside the step-over that sends the request — the fastest a reply can ever come back —
 * so this also proves the wait is armed before the request leaves. {@code booking-quote-fork} is the
 * guide's alternative shape, a FORK of the request and a WAIT_FOR_MESSAGE.
 */
class RequestResponseGuideE2eTest extends AbstractE2eTest {

    @Test
    void theRequesterGetsTheAnswerOfTheServiceItStarted() {
        worker.on("price", TestWorker.succeed(TestWorker.var("price", "129.00")));

        createProcess("booking-quote", "booking-quote-B-1", new Variable("item", "room-101"));

        assertBooked("booking-quote-B-1", "ask-quote");
    }

    @Test
    void theForkedAlternativeGetsTheAnswerToo() {
        worker.on("price", TestWorker.succeed(TestWorker.var("price", "129.00")));

        createProcess("booking-quote-fork", "booking-quote-B-2", new Variable("item", "room-101"));

        assertBooked("booking-quote-B-2", "wait-quote");
    }

    private void assertBooked(String businessKey, String waitingStep) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step(businessKey, waitingStep).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "book").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process(businessKey).getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("price", "129.00"));

        var service = process("quote-service-" + businessKey);
        assertThat(service.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(step("quote-service-" + businessKey, "reply").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
