package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.QuoteResponder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Asynchronous request/response, memory mode: does a reply that comes back immediately find its
 * WAIT_FOR_MESSAGE? Messages that match no PENDING wait are dropped, so the wait must be armed
 * before the request leaves. See guides/request-response.md.
 */
@Import(QuoteResponder.class)
class RequestResponseE2eTest extends AbstractE2eTest {

    @Autowired QuoteResponder responder;

    private void ask(String definition, String businessKey) {
        createProcess(definition, businessKey, new Variable("item", "room-101"));
    }

    private void askForSomethingNobodySells(String definition, String businessKey) {
        createProcess(definition, businessKey, new Variable("item", QuoteResponder.SILENT));
    }

    private void assertAnswered(String businessKey) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step(businessKey, "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "use-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process(businessKey).getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("price", "42"));
    }

    private void assertReplyLost(String businessKey) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step(businessKey, "wait").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(step(businessKey, "no-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "use-quote").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);
    }

    /** PUBLISH_EVENT then WAIT_FOR_MESSAGE: the wait starts only after the event is out, so a fast reply is dropped. */
    @Test
    void aReplyToAPublishedRequestIsLostWhenTheWaitComesAfterIt() {
        ask("rr-publish-sequential", "rr-mem-pub-seq");

        assertReplyLost("rr-mem-pub-seq");
        assertThat(responder.repliesTo("rr-mem-pub-seq")).isEqualTo(1);
    }

    /** FORK with the wait listed before the request: both start in one pass, the wait is PENDING first. */
    @Test
    void aForkedWaitListedBeforeThePublishCatchesAnImmediateReply() {
        ask("rr-publish-fork", "rr-mem-pub-fork");

        assertAnswered("rr-mem-pub-fork");
    }

    /** No reply at all: the forked wait times out and the flow takes its on-timeout route, past the unfinished JOIN. */
    @Test
    void aForkedWaitWithNoReplyTakesItsTimeoutRoute() {
        askForSomethingNobodySells("rr-publish-fork", "rr-mem-pub-fork-silent");

        assertReplyLost("rr-mem-pub-fork-silent");
        assertThat(responder.repliesTo("rr-mem-pub-fork-silent")).isZero();
        assertThat(step("rr-mem-pub-fork-silent", "ask").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    /** The order of the two branches in the definition does not matter: every step of the pass is started before any is saved. */
    @Test
    void aForkedWaitListedAfterThePublishCatchesAnImmediateReplyToo() {
        ask("rr-publish-fork-ask-first", "rr-mem-pub-fork-ask-first");

        assertAnswered("rr-mem-pub-fork-ask-first");
        assertThat(responder.repliesTo("rr-mem-pub-fork-ask-first")).isEqualTo(1);
    }

    /** HTTP_CALL then WAIT_FOR_MESSAGE: a callback that beats the worker's report is dropped. */
    @Test
    void aCallbackThatBeatsTheHttpCallsCompletionIsLostWhenTheWaitComesAfterIt() {
        worker.on("ask", (request, callback, invocation) -> {
            responder.reply("rr-mem-http-seq");
            callback.complete();
        });

        ask("rr-http-sequential", "rr-mem-http-seq");

        assertReplyLost("rr-mem-http-seq");
    }

    /** FORK: the wait is PENDING before the HTTP task is even dispatched, so the early callback is caught. */
    @Test
    void aForkedWaitCatchesACallbackThatBeatsTheHttpCallsCompletion() {
        worker.on("ask", (request, callback, invocation) -> {
            responder.reply("rr-mem-http-fork");
            callback.complete();
        });

        ask("rr-http-fork", "rr-mem-http-fork");

        assertAnswered("rr-mem-http-fork");
    }
}
