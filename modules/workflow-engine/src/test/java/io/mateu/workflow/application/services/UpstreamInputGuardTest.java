package io.mateu.workflow.application.services;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.input.InputLimits.InputRejectedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-LIM-10..18 — which fields of which arriving events are measured, and — the assertion that decides where a
 * refusal ends up — that a refusal is never mistaken for a retryable failure.
 */
class UpstreamInputGuardTest {

    private static final String TOO_LONG_KEY = "k".repeat(InputLimits.MAX_IDENTIFIER_LENGTH + 1);
    private static final String TOO_BIG_VALUE = "x".repeat(InputLimits.MAX_VALUE_LENGTH + 1);

    @Test
    void aProcessCreationWithAnOversizedBusinessKeyIsRefused() {
        assertThatThrownBy(() -> UpstreamInputGuard.check(
                new ProcessCreationRequested("wd-1", TOO_LONG_KEY, List.of())))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("businessKey");
    }

    @Test
    void aProcessCreationWithAnOversizedVariableIsRefused() {
        assertThatThrownBy(() -> UpstreamInputGuard.check(new ProcessCreationRequested(
                "wd-1", "BK", List.of(new Variable("payload", TOO_BIG_VALUE)))))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void aMessageWithAnOversizedCorrelationKeyIsRefused() {
        assertThatThrownBy(() -> UpstreamInputGuard.check(
                new MessageReceived("payment-confirmed", TOO_LONG_KEY, List.of())))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("correlationKey");
    }

    /** A worker is as untrusted as a browser: it is somebody else's code answering over a topic. */
    @Test
    void aWorkerReplyCarryingTooMuchIsRefused() {
        assertThatThrownBy(() -> UpstreamInputGuard.check(new TaskStatusChanged(
                "te-1", TaskStatus.COMPLETED, List.of(new Variable("out", TOO_BIG_VALUE)), "p-1")))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("a worker reply");
    }

    @Test
    void anOversizedLogLineOrInjectedStepsDocumentIsRefused() {
        assertThatThrownBy(() -> UpstreamInputGuard.check(
                new TaskLogEmitted("te-1", MessageType.Info, TOO_BIG_VALUE)))
                .isInstanceOf(InputRejectedException.class);

        assertThatThrownBy(() -> UpstreamInputGuard.check(
                new StepsInjected("te-1", "p-1", TOO_BIG_VALUE)))
                .isInstanceOf(InputRejectedException.class);
    }

    @Test
    void aControlRequestNamingAnImpossibleProcessIsRefused() {
        assertThatThrownBy(() -> UpstreamInputGuard.check(new RetryProcessRequested(TOO_LONG_KEY)))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("processId");
    }

    @Test
    void ordinaryEventsPass() {
        assertThatCode(() -> {
            UpstreamInputGuard.check(new ProcessCreationRequested("wd-1", "BK",
                    List.of(new Variable("amount", "100"))));
            UpstreamInputGuard.check(new MessageReceived("m", "BK", List.of()));
            UpstreamInputGuard.check(new TaskStatusChanged("te-1", TaskStatus.COMPLETED, List.of(), "p-1"));
            UpstreamInputGuard.check(new RetryProcessRequested("p-1"));
        }).doesNotThrowAnyException();
    }

    /** An event shape with nothing untrusted on it is left alone rather than guessed at. */
    @Test
    void anEventWithNothingToMeasurePassesUnchecked() {
        assertThatCode(() -> UpstreamInputGuard.check(new TimerCheckRequested("p-1")))
                .doesNotThrowAnyException();
    }

    /**
     * The assertion that makes the dead letter happen. The consumer redelivers a retryable failure
     * for ever and parks everything else; an oversized event will be oversized on every redelivery,
     * so being classified as <em>not</em> retryable is precisely what sends it to the dead-letter
     * destination instead of round the loop.
     */
    @Test
    void aRefusalIsNotRetryableSoTheConsumerParksItRatherThanRedeliveringItForever() {
        var refusal = new InputRejectedException("too big");

        assertThat(EventFailures.isRetryable(refusal)).isFalse();
    }
}
