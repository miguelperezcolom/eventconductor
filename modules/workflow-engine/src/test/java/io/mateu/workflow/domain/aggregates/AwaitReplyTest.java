package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.dtos.events.domain.MessageSubscriptionChanged;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AwaitReplyTest {

    private static final AwaitReply REPLY = new AwaitReply("quote-ready", "requestId", null);

    private static Step step(String id, StepType type, String precondition) {
        return new Step(id, "wd-1", type, id, null, precondition, null, null, false, null, null, null,
                null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static Step publish(AwaitReply reply) {
        return step("ask", StepType.PUBLISH_EVENT, "start")
                .withEvent(new PublishEvent("quotes", "quote.requested", null, null, null, null, null))
                .withAwaitReply(reply);
    }

    private static Step http(AwaitReply reply) {
        return JsonSerializer.pojoFromJson("""
                {"id": "ask", "workflowDefinitionId": "wd-1", "type": "HTTP_CALL", "name": "Ask",
                 "preconditionStepId": "start",
                 "http": {"url": "https://pricing.example.com/quotes", "method": "POST"}}
                """, Step.class).withAwaitReply(reply);
    }

    private static WorkflowDefinition definition(Step step) {
        return new WorkflowDefinition("wd-1", "Ask", 1, null, false, 0, false, null, 0,
                List.of(step("start", StepType.START, null), step));
    }

    private static Process process() {
        return Process.builder().id("p-1").businessKey("bk-1")
                .variables(List.of(new Variable("requestId", "R-1"))).build();
    }

    @Test
    void theBlockRoundTripsAndIsLeftOutWhenAbsent() {
        var reply = new AwaitReply("quote-ready", "requestId",
                new EventWait("quote-replies", "quote.ready", "${data.requestId}", null, Map.of("price", "${data.price}")));
        var back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(publish(reply)), Step.class);
        assertThat(back.awaitReply()).isEqualTo(reply);
        assertThat(JsonSerializer.toJson(publish(null))).doesNotContain("awaitReply");
        assertThat(new AwaitReply(" ", " ", null)).isEqualTo(new AwaitReply(null, null, null));
    }

    @Test
    void aStepThatAwaitsAReplyReadsAsAMessageWait() {
        var step = publish(REPLY);
        assertThat(step.awaitsReply()).isTrue();
        assertThat(step.awaitsMessage()).isTrue();
        assertThat(step.messageWait().messageName()).isEqualTo("quote-ready");
        assertThat(step.messageWait().correlationExpression()).isEqualTo("requestId");
        assertThat(publish(null).awaitsMessage()).isFalse();
        // Only the two request types await replies: on anything else the block is not read.
        var action = step("act", StepType.ACTION, "start").withAwaitReply(REPLY);
        assertThat(action.awaitsMessage()).isFalse();
        assertThat(action.messageWait()).isSameAs(action);
    }

    @Test
    void theInvariantsAcceptACompleteReplyAndRejectTheRest() {
        assertThatNoException().isThrownBy(() -> definition(publish(REPLY)).checkInvariants());
        assertThatNoException().isThrownBy(() -> definition(http(REPLY)).checkInvariants());
        // A deadline is something a PUBLISH_EVENT can miss once it waits for a reply.
        assertThatNoException().isThrownBy(() -> definition(publish(REPLY)
                .withDeadline(new Moment("${dueAt}", null, null, null, null))).checkInvariants());
        assertThatThrownBy(() -> definition(publish(null)
                .withDeadline(new Moment("${dueAt}", null, null, null, null))).checkInvariants())
                .hasMessageContaining("declares a deadline");

        assertThatThrownBy(() -> definition(publish(new AwaitReply("quote-ready", null, null))).checkInvariants())
                .hasMessageContaining("awaitReply: a correlationExpression is required");
        assertThatThrownBy(() -> definition(step("act", StepType.ACTION, "start").withTopic("t")
                .withAwaitReply(REPLY)).checkInvariants())
                .hasMessageContaining("only an HTTP_CALL or a PUBLISH_EVENT awaits a reply");
        assertThatThrownBy(() -> definition(http(null).withMessageName("quote-ready")).checkInvariants())
                .hasMessageContaining("declares messageName, which an HTTP_CALL does not read");
        assertThatThrownBy(() -> definition(publish(new AwaitReply("quote-ready", "requestId",
                new EventWait("quote-replies", "quote.ready", "${data.requestId", null, null)))).checkInvariants())
                .hasMessageContaining("awaitReply.onEvent");
        assertThatThrownBy(() -> definition(publish(new AwaitReply("quote-ready", "requestId",
                new EventWait(null, "quote.ready", null, null, null)))).checkInvariants())
                .hasMessageContaining("awaitReply.onEvent: a destination is required");
    }

    @Test
    void aPublishIsArmedAsTheWaiterWhenItStarts() {
        var se = StepExecution.create(publish(REPLY), "p-1", 0);

        se.start(process());

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(se.getAwaitingMessageName()).isEqualTo("quote-ready");
        assertThat(se.getAwaitingCorrelationKey()).isEqualTo("R-1");
        assertThat(se.popEvents()).anyMatch(e -> e instanceof MessageSubscriptionChanged changed && changed.waiting()
                && "R-1".equals(changed.correlationKey()));
        // Still waiting after a rearm, with the key following the process.
        var moved = Process.builder().id("p-1").businessKey("bk-1")
                .variables(List.of(new Variable("requestId", "R-2"))).build();
        assertThat(se.rearmedFor(moved).getAwaitingCorrelationKey()).isEqualTo("R-2");
    }

    @Test
    void anHttpCallIsArmedBeforeItsTaskIsDispatched() {
        var se = StepExecution.create(http(REPLY), "p-1", 0);

        se.start(process());

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        var events = se.popEvents();
        var subscribed = events.stream().filter(e -> e instanceof MessageSubscriptionChanged).findFirst().orElseThrow();
        var dispatched = events.stream().filter(e -> e instanceof TaskExecutionRequested).findFirst().orElseThrow();
        assertThat(events.indexOf(subscribed)).isLessThan(events.indexOf(dispatched));
    }

    @Test
    void aReplyWithoutAMessageNameFailsTheStepThroughTheNormalPipeline() {
        var se = StepExecution.create(publish(new AwaitReply(null, "requestId", null)), "p-1", 0);

        se.start(process());

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(se.popEvents()).anyMatch(e -> e instanceof TaskLogEmitted log
                && log.message().contains("awaitReply has no message name"));
    }

    @Test
    void aCorrelationThatCannotBeEvaluatedMatchesNothingAndAnnouncesNoSubscription() {
        var se = StepExecution.create(publish(new AwaitReply("quote-ready", "missing.field.call()", null)), "p-1", 0);

        se.start(process());

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(se.getAwaitingCorrelationKey()).isNull();
        assertThat(se.popEvents()).noneMatch(e -> e instanceof MessageSubscriptionChanged);
    }
}
