package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwaitReplyRulesTest {

    @Test
    void aStepWithoutAwaitReplyHasNothingToCheck() {
        assertThat(AwaitReplyRules.violations("a", "ACTION", false, null, null, false, null, null)).isEmpty();
    }

    @Test
    void aCompleteAwaitReplyOnAnHttpCallOrAPublishIsValid() {
        assertThat(AwaitReplyRules.violations("ask", "HTTP_CALL", true, "quote-ready", "businessKey",
                false, null, null)).isEmpty();
        assertThat(AwaitReplyRules.violations("ask", "PUBLISH_EVENT", true, "quote-ready", "requestId",
                true, "quote-replies", "com.acme.quote.ready")).isEmpty();
    }

    @Test
    void onlyAnHttpCallOrAPublishAwaitsAReply() {
        assertThat(AwaitReplyRules.violations("act", "ACTION", true, "m", "businessKey", false, null, null))
                .containsExactly("Step 'act' awaitReply: only an HTTP_CALL or a PUBLISH_EVENT awaits a reply.");
        assertThat(AwaitReplyRules.violations("w", "WAIT_FOR_MESSAGE", true, "m", "businessKey", false, null, null))
                .singleElement().asString().contains("is the wait itself");
    }

    @Test
    void messageNameAndCorrelationExpressionAreRequired() {
        assertThat(AwaitReplyRules.violations("ask", "HTTP_CALL", true, " ", null, false, null, null))
                .anySatisfy(v -> assertThat(v).contains("a messageName is required"))
                .anySatisfy(v -> assertThat(v).contains("a correlationExpression is required"));
    }

    @Test
    void itsOnEventFollowsTheOnEventRules() {
        assertThat(AwaitReplyRules.violations("ask", "PUBLISH_EVENT", true, "m", "businessKey", true, "bad name", null))
                .anySatisfy(v -> assertThat(v).startsWith("Step 'ask' awaitReply.onEvent: 'bad name' is not a valid destination"))
                .anySatisfy(v -> assertThat(v).contains("a type is required"));
    }

    @Test
    void waitFieldsOnTheStepItselfAreReportedOnTheTypesThatAwaitReplies() {
        assertThat(AwaitReplyRules.misplacedWaitFieldWarnings("ask", "HTTP_CALL", true, false))
                .containsExactly("Step 'ask' declares messageName, which an HTTP_CALL ignores; to wait for a reply,"
                        + " put it inside awaitReply.");
        assertThat(AwaitReplyRules.misplacedWaitFieldWarnings("ask", "PUBLISH_EVENT", false, true))
                .singleElement().asString().contains("declares correlationExpression");
        assertThat(AwaitReplyRules.misplacedWaitFieldWarnings("ask", "PUBLISH_EVENT", false, false)).isEmpty();
        assertThat(AwaitReplyRules.misplacedWaitFieldWarnings("w", "WAIT_FOR_MESSAGE", true, true)).isEmpty();
    }

    @Test
    void anOnEventOnAnHttpCallPointsAtAwaitReply() {
        assertThat(EventTriggerRules.onEventPlacementViolations("ask", "HTTP_CALL", true))
                .containsExactly("Step 'ask' declares onEvent, which only a WAIT_FOR_MESSAGE reads"
                        + " (an HTTP_CALL waiting for a reply takes it inside awaitReply).");
    }
}
