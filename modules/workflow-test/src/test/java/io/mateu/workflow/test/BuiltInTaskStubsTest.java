package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP_CALL and LLM_CALL never leave the test: stubs answer them, with the step's own output mapping. */
@EventConductorTest
class BuiltInTaskStubsTest {

    @Test
    void anHttpCallIsAnsweredByItsStubAndRetriedOnA503(WorkflowTestRuntime runtime) {
        var charge = runtime.whenHttpCall("charge")
                .thenRespond(503, "busy")
                .thenRespond(201, "{\"id\":\"ch_42\"}", Map.of("Location", "/charges/ch_42"));

        var process = runtime.start("http-charge", "http-1", Map.of("bookingId", "B-1", "amount", 99.5));
        assertThat(process).isWaitingAt("charge");

        runtime.advanceTime(Duration.ofMinutes(1));

        assertThat(process).isCompleted().hasVariable("chargeId", "ch_42").hasVariable("chargeStatus", 201);
        assertThat(charge.requests()).hasSize(2);
        assertThat(charge.lastRequest()).containsEntry("method", "POST")
                .containsEntry("url", "https://payments.example.com/charges/B-1");
    }

    @Test
    void aClientErrorIsFinal(WorkflowTestRuntime runtime) {
        runtime.whenHttpCall("charge").thenRespond(404, "no such booking");

        var process = runtime.start("http-charge", "http-2", Map.of("bookingId", "B-2", "amount", 1));

        assertThat(process).isFailed();
        assertThat(runtime.invocationsOf("charge")).hasSize(1);
    }

    @Test
    void anIoErrorIsRetriedAndAnUnstubbedCallFailsWithoutTheNetwork(WorkflowTestRuntime runtime) {
        runtime.whenHttpCall("charge").thenIoError();
        var flaky = runtime.start("http-charge", "http-3", Map.of("bookingId", "B-3", "amount", 1));
        runtime.advanceTime(Duration.ofMinutes(5));
        assertThat(flaky).isFailed();
        assertThat(runtime.invocationsOf("charge")).hasSize(3);
    }

    @Test
    void anUnstubbedHttpCallFails(WorkflowTestRuntime runtime) {
        var process = runtime.start("http-charge", "http-4", Map.of("bookingId", "B-4", "amount", 1));
        assertThat(process).isFailed().hasStepStatus("charge", StepExecutionStatus.ERROR);
    }

    @Test
    void anLlmCallIsAnsweredByItsStub(WorkflowTestRuntime runtime) {
        var model = runtime.whenLlmCall("triage").thenAnswer("not json").thenAnswer("{\"category\":\"noise\"}");

        var process = runtime.start("triage", "llm-1", Map.of("guest", "Ann", "complaint", "the party next door"));
        runtime.advanceTime(Duration.ofMinutes(1));

        assertThat(process).isCompleted().hasVariable("category", "noise")
                .hasVariable("answer", "{\"category\":\"noise\"}");
        assertThat(model.lastRequest()).containsEntry("prompt", "Ann wrote: the party next door");
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void anLlmFailureAndAMissingStubFailTheStep(WorkflowTestRuntime runtime) {
        runtime.whenLlmCall("triage").thenFail("LLM_RATE_LIMITED", false);
        var limited = runtime.start("triage", "llm-2", Map.of("guest", "Bo", "complaint", "cold"));
        assertThat(limited).isFailed();

        var unstubbed = runtime.start("http-charge", "llm-3", Map.of("bookingId", "B-5", "amount", 1));
        assertThat(unstubbed).isFailed();
    }

    @Test
    void aStepMockAlsoAnswersABuiltInTask(WorkflowTestRuntime runtime) {
        runtime.whenStep("charge").thenComplete(Map.of("chargeId", "by-step-mock"));
        var process = runtime.start("http-charge", "http-5", Map.of("bookingId", "B-6", "amount", 1));
        assertThat(process).isCompleted().hasVariable("chargeId", "by-step-mock");
    }
}
