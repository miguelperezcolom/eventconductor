package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.QuoteResponder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asynchronous request/response on the durable path (embedded + JPA, real outbox relay): the
 * request leaves only after the transaction that started its steps commits, so a wait started in
 * that same transaction is always armed in time — whatever the order of the steps.
 */
@Import(QuoteResponder.class)
class RequestResponseJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired QuoteResponder responder;

    private void ask(String definition, String businessKey) {
        createProcess(definition, businessKey, new Variable("item", "room-101"));
    }

    private void assertAnswered(String businessKey) {
        awaitStatus(businessKey, ProcessStatus.COMPLETED);
        assertThat(step(businessKey, "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "use-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(responder.repliesTo(businessKey)).isEqualTo(1);
    }

    private void assertReplyLost(String businessKey) {
        awaitStatus(businessKey, ProcessStatus.COMPLETED);
        assertThat(step(businessKey, "wait").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(step(businessKey, "no-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(responder.repliesTo(businessKey)).isEqualTo(1);
    }

    @Test
    void aReplyToAPublishedRequestIsLostWhenTheWaitComesAfterIt() {
        ask("rr-publish-sequential", "rr-jpa-pub-seq");

        assertReplyLost("rr-jpa-pub-seq");
    }

    @Test
    void aForkedWaitCatchesAnImmediateReplyToAPublishedRequest() {
        ask("rr-publish-fork", "rr-jpa-pub-fork");

        assertAnswered("rr-jpa-pub-fork");
    }

    /** Durable modes: the order of the steps in the definition does not matter — the outbox is relayed after commit. */
    @Test
    void theStepOrderDoesNotMatterOnTheDurablePath() {
        ask("rr-publish-fork-ask-first", "rr-jpa-pub-fork-ask-first");

        assertAnswered("rr-jpa-pub-fork-ask-first");
    }

    @Test
    void aCallbackThatBeatsTheHttpCallsCompletionIsLostWhenTheWaitComesAfterIt() {
        worker.on("ask", (request, callback, invocation) -> {
            responder.reply("rr-jpa-http-seq");
            callback.complete();
        });

        ask("rr-http-sequential", "rr-jpa-http-seq");

        assertReplyLost("rr-jpa-http-seq");
    }

    @Test
    void aForkedWaitCatchesACallbackThatBeatsTheHttpCallsCompletion() {
        worker.on("ask", (request, callback, invocation) -> {
            responder.reply("rr-jpa-http-fork-ask-first");
            callback.complete();
        });

        ask("rr-http-fork-ask-first", "rr-jpa-http-fork-ask-first");

        assertAnswered("rr-jpa-http-fork-ask-first");
    }
}
