package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.QuoteResponder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Asynchronous request/response in a single step, memory mode: an HTTP_CALL or a PUBLISH_EVENT with an
 * {@code awaitReply} is armed as the waiter before its request leaves, so the fastest possible responder
 * — one that answers on the thread that delivers the request — never loses its reply. See
 * guides/request-response.md.
 */
@Import(QuoteResponder.class)
class AwaitReplyE2eTest extends AbstractE2eTest {

    @Autowired QuoteResponder responder;
    @Autowired InboundEventService inbound;

    private void ask(String definition, String businessKey) {
        createProcess(definition, businessKey, new io.mateu.workflow.dtos.Variable("item", "room-101"));
    }

    private void assertAnswered(String businessKey) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step(businessKey, "ask").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "use-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "no-quote").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process(businessKey).getVariables()).contains(new Variable("price", "42"));
    }

    @Test
    void aPublishThatAwaitsItsReplyCatchesAnImmediateOne() {
        ask("rr-publish-await", "aw-mem-pub");

        assertAnswered("aw-mem-pub");
        assertThat(responder.repliesTo("aw-mem-pub")).isEqualTo(1);
    }

    @Test
    void anHttpCallThatAwaitsItsReplyCatchesACallbackSentBeforeItsResponse() {
        worker.on("ask", (request, callback, invocation) -> {
            responder.reply("aw-mem-http");
            // The response comes after the reply: the step is already complete and the report is
            // ignored, its mapped variables with it.
            callback.complete(List.of(var("ticket", "T-late")));
        });

        ask("rr-http-await", "aw-mem-http");

        assertAnswered("aw-mem-http");
        assertThat(process("aw-mem-http").getVariables()).extracting(Variable::name).doesNotContain("ticket");
    }

    @Test
    void anHttpResponseBeforeTheReplyIsRecordedButDoesNotCompleteTheStep() {
        worker.on("ask", (request, callback, invocation) -> callback.complete(List.of(var("ticket", "T-1"))));

        ask("rr-http-await", "aw-mem-http-first");

        assertThat(step("aw-mem-http-first", "ask").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(process("aw-mem-http-first").getVariables()).contains(new Variable("ticket", "T-1"));
        assertThat(step("aw-mem-http-first", "use-quote").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(logsOf("aw-mem-http-first")).anySatisfy(line ->
                assertThat(line).contains("Request answered; waiting for reply 'quote-ready'"));

        responder.reply("aw-mem-http-first");

        assertAnswered("aw-mem-http-first");
        assertThat(process("aw-mem-http-first").getVariables()).contains(new Variable("ticket", "T-1"));
    }

    @Test
    void aWorkerRunningReportLeavesTheStepWaiting() {
        worker.on("ask", (request, callback, invocation) -> {
            updateStepExecutionUseCase.handle(new io.mateu.workflow.application.usecases.stepexecution.update
                    .UpdateStepExecutionCommand(request.taskExecutionId(), List.of(), "", StepExecutionStatus.RUNNING));
            responder.reply("aw-mem-http-running");
            callback.complete();
        });

        ask("rr-http-await", "aw-mem-http-running");

        assertAnswered("aw-mem-http-running");
    }

    @Test
    void noReplyTakesTheTimeoutRoute() {
        createProcess("rr-publish-await", "aw-mem-silent",
                new io.mateu.workflow.dtos.Variable("item", QuoteResponder.SILENT));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("aw-mem-silent").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("aw-mem-silent", "ask").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(step("aw-mem-silent", "no-quote").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("aw-mem-silent", "use-quote").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);

        // A reply after the timeout matches nothing: the step is not waiting any more.
        responder.reply("aw-mem-silent");
        assertThat(step("aw-mem-silent", "ask").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
    }

    @Test
    void aTimedOutRequestIsSentAgainAsANewEventAndTheWaitRearmed() {
        ask("rr-publish-await-retry", "aw-mem-retry");

        assertAnswered("aw-mem-retry");
        var ids = responder.requestIdsFor("aw-mem-retry");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(1)).isEqualTo(ids.get(0) + "-1");
        assertThat(step("aw-mem-retry", "ask").getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aFailedRequestIsRetriedAndTheWaitRearmed() {
        worker.on("ask", (request, callback, invocation) -> {
            if (invocation == 1) {
                // The request failed: the step fails as any HTTP_CALL does, and a reply now would match nothing.
                callback.fail("503 Service Unavailable");
                return;
            }
            responder.reply("aw-mem-http-retry");
            callback.complete();
        });

        ask("rr-http-await-retry", "aw-mem-http-retry");

        assertAnswered("aw-mem-http-retry");
        assertThat(worker.invocationsOf("ask")).isEqualTo(2);
        assertThat(step("aw-mem-http-retry", "ask").getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aRetriedRequestIgnoresTheLateResponseOfTheAttemptItReplaced() {
        var callbacks = new java.util.concurrent.ConcurrentHashMap<Integer, io.mateu.workflow.e2e.support.TestWorker.WorkerCallback>();
        // Neither attempt answers on its own: the first times out and the step is dispatched again.
        worker.on("ask", (request, callback, invocation) -> callbacks.put(invocation, callback));

        ask("rr-http-await-retry-timeout", "aw-mem-late");
        await().atMost(Duration.ofSeconds(10)).until(() -> callbacks.containsKey(2));
        assertThat(step("aw-mem-late", "ask").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // The first attempt's response arrives late: it is about an attempt the step no longer runs.
        callbacks.get(1).complete(List.of(var("ticket", "T-stale")));
        assertThat(step("aw-mem-late", "ask").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(process("aw-mem-late").getVariables()).extracting(Variable::name).doesNotContain("ticket");

        // The current attempt's response is recorded, and the step keeps waiting for its reply.
        callbacks.get(2).complete(List.of(var("ticket", "T-2")));
        assertThat(step("aw-mem-late", "ask").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(process("aw-mem-late").getVariables()).contains(new Variable("ticket", "T-2"));

        responder.reply("aw-mem-late");

        assertAnswered("aw-mem-late");
        assertThat(process("aw-mem-late").getVariables()).contains(new Variable("ticket", "T-2"));
    }

    @Test
    void anEventReplyIsCaughtEvenWhenItComesBackImmediately() {
        ask("rr-event-await", "aw-mem-event");

        assertAnswered("aw-mem-event");
    }

    @Test
    void theSameReplyEventIsNotTakenTwice() {
        var businessKey = "aw-mem-twice";
        ask("rr-event-await-twice", businessKey);
        assertThat(step(businessKey, "ask-1").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        var first = inbound.receive(reply("q-1", businessKey, "10"));
        assertThat(first.delivered()).isEqualTo(1);
        assertThat(step(businessKey, "ask-1").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step(businessKey, "ask-2").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        var redelivered = inbound.receive(reply("q-1", businessKey, "10"));
        assertThat(redelivered.delivered()).isZero();
        assertThat(redelivered.duplicates()).containsExactly("wait:quote-event-ready/" + businessKey);
        assertThat(step(businessKey, "ask-2").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        inbound.receive(reply("q-2", businessKey, "20"));
        assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process(businessKey).getVariables()).contains(new Variable("price", "20"));
    }

    private static InboundEvent reply(String id, String requestId, String price) {
        return new InboundEvent(QuoteResponder.REPLIES, id, "pricing/e2e", "com.acme.quote.ready", null, null, null,
                "application/json", "{\"requestId\":\"" + requestId + "\",\"price\":\"" + price + "\"}", Map.of());
    }
}
