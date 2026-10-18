package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.QuoteResponder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asynchronous request/response in a single step on the durable path (embedded + JPA, real outbox
 * relay): the wait of an {@code awaitReply} is committed in the transaction that writes the request's
 * outbox row (or the HTTP task's dispatch), so the fastest responder never loses its reply.
 */
@Import(QuoteResponder.class)
class AwaitReplyJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired QuoteResponder responder;

    private void ask(String definition, String businessKey) {
        createProcess(definition, businessKey, new io.mateu.workflow.dtos.Variable("item", "room-101"));
    }

    private void assertAnswered(String businessKey) {
        awaitStatus(businessKey, ProcessStatus.COMPLETED);
        assertThat(step(businessKey, "ask").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "use-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process(businessKey).getVariables()).contains(new Variable("price", "42"));
    }

    @Test
    void aPublishThatAwaitsItsReplyCatchesAnImmediateOne() {
        ask("rr-publish-await", "aw-jpa-pub");

        assertAnswered("aw-jpa-pub");
        assertThat(responder.repliesTo("aw-jpa-pub")).isEqualTo(1);
    }

    @Test
    void anHttpCallThatAwaitsItsReplyCatchesACallbackSentBeforeItsResponse() {
        worker.on("ask", (request, callback, invocation) -> {
            responder.reply("aw-jpa-http");
            callback.complete(List.of(var("ticket", "T-late")));
        });

        ask("rr-http-await", "aw-jpa-http");

        assertAnswered("aw-jpa-http");
    }

    @Test
    void anHttpResponseBeforeTheReplyIsRecordedAndTheReplyCompletesTheStep() {
        worker.on("ask", (request, callback, invocation) -> {
            callback.complete(List.of(var("ticket", "T-1")));
            responder.reply("aw-jpa-http-first");
        });

        ask("rr-http-await", "aw-jpa-http-first");

        assertAnswered("aw-jpa-http-first");
        assertThat(process("aw-jpa-http-first").getVariables()).contains(new Variable("ticket", "T-1"));
    }

    @Test
    void anEventReplyIsCaughtEvenWhenItComesBackImmediately() {
        ask("rr-event-await", "aw-jpa-event");

        assertAnswered("aw-jpa-event");
    }

    @Test
    void aTimedOutRequestIsSentAgainAndTheWaitRearmed() {
        ask("rr-publish-await-retry", "aw-jpa-retry");

        assertAnswered("aw-jpa-retry");
        var ids = responder.requestIdsFor("aw-jpa-retry");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(1)).isEqualTo(ids.get(0) + "-1");
    }

    @Test
    void noReplyTakesTheTimeoutRoute() {
        createProcess("rr-publish-await", "aw-jpa-silent",
                new io.mateu.workflow.dtos.Variable("item", QuoteResponder.SILENT));

        awaitStatus("aw-jpa-silent", ProcessStatus.COMPLETED);
        assertThat(step("aw-jpa-silent", "ask").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(step("aw-jpa-silent", "no-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
