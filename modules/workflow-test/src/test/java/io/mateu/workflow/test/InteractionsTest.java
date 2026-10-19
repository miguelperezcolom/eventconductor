package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ProcessReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Messages, domain events, user tasks, synchronous replies and manually answered tasks. */
@EventConductorTest
class InteractionsTest {

    @Autowired
    WorkflowTestRuntime runtime;

    @Test
    void aReplyStepAnswersTheCaller() {
        runtime.whenTask("price").thenAnswer(task ->
                TaskOutcome.complete(Map.of("price", "deluxe".equals(task.variable("roomType")) ? 240 : 120)));

        var quote = runtime.start("quote", "quote-1", Map.of("roomType", "deluxe"));

        assertThat(quote).isCompleted().hasReplied("{\"roomType\":\"deluxe\",\"price\":\"240\"}")
                .hasReplyOutcome(ProcessReply.Outcome.REPLIED).hasVariable("price", 240);
    }

    @Test
    void aUserTaskWaitsForThePersonAndCarriesOn() {
        runtime.whenTask("notify").thenComplete();

        var approval = runtime.start("approval", "approval-1", Map.of());
        assertThat(approval).isWaitingAt("approve");
        assertThat(runtime.invocationsOf("approve").getFirst().variable("formId")).isEqualTo("approval-form");

        runtime.completeUserTask(approval, "approve", Map.of("approved", true, "comment", "fine"));

        assertThat(approval).isCompleted().hasVariable("approved", true).hasVariable("comment", "fine");
        assertThatThrownBy(() -> runtime.completeUserTask(approval, "approve", Map.of()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("no task waiting");
    }

    @Test
    void anUnmockedTaskWaitsUntilTheTestAnswersIt() {
        var approval = runtime.start("approval", "approval-2", Map.of());
        runtime.completeUserTask(approval, "approve", Map.of());
        assertThat(approval).isWaitingAt("notify");

        assertThatThrownBy(() -> runtime.completeUserTask(approval, "notify", Map.of()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("not a USER_TASK");
        runtime.failTask(approval, "notify", "SMTP_DOWN", false);

        assertThat(approval).isFailed();
    }

    @Test
    void aTaskCanBeCompletedByHand() {
        var approval = runtime.start("approval", "approval-3", Map.of());
        runtime.completeUserTask(approval, "approve", Map.of());
        runtime.completeTask(approval, "notify", Map.of("sent", "yes"));
        assertThat(approval).isCompleted().hasVariables(Map.of("sent", "yes"));
    }

    @Test
    void domainEventsStartAndResumeProcessesAndPublishedEventsAreRecorded() {
        var result = runtime.publishEvent("bookings", "com.acme.booking.created", Map.of("bookingId", "B-7"));
        assertThat(result.started()).isEqualTo(1);

        var booking = runtime.process("booking-B-7");
        assertThat(booking).isWaitingAt("paid").hasVariable("bookingId", "B-7");

        runtime.publishEvent("payments", "com.acme.payment.captured", "{\"bookingId\":\"B-7\",\"paymentId\":\"P-1\"}");

        assertThat(booking).isCompleted().hasVariable("paymentId", "P-1").hasPublishedEvent("com.acme.booking.paid");
        assertThat(runtime.publishedEvents(booking)).singleElement()
                .satisfies(event -> assertThat(event.data()).contains("B-7"));
    }

    @Test
    void aCancelledProcessGoesNoFurther() {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete();
        runtime.whenTask("capture-payment").thenComplete();

        var booking = runtime.start("booking-saga", "cancel-1", Map.of());
        runtime.cancel(booking);
        runtime.advanceTime(java.time.Duration.ofDays(4));

        assertThat(booking).isCancelled().hasNotPassedSteps("capture");
        assertThat(runtime.invocationsOf("capture")).isEmpty();
    }

    @Test
    void failedAssertionsDescribeTheProcess() {
        var approval = runtime.start("approval", null, Map.of());
        assertThat(approval.businessKey()).startsWith("approval-");

        assertThatThrownBy(() -> assertThat(approval).isCompleted())
                .hasMessageContaining("to be COMPLETED but it is RUNNING").hasMessageContaining("approve");
        assertThatThrownBy(() -> assertThat(approval).hasPassedSteps("approve"))
                .hasMessageContaining("has not passed [approve]");
        assertThatThrownBy(() -> assertThat(approval).hasNotPassedSteps("start"))
                .hasMessageContaining("it passed [start]");
        assertThatThrownBy(() -> assertThat(approval).isWaitingAt("start"))
                .hasMessageContaining("to be waiting at 'start'");
        assertThatThrownBy(() -> assertThat(approval).hasVariable("missing", 1))
                .hasMessageContaining("to have variable 'missing'");
        assertThatThrownBy(() -> assertThat(approval).hasVariable("missing"))
                .hasMessageContaining("to have variable 'missing'");
        assertThatThrownBy(() -> assertThat(approval).hasReplied())
                .hasMessageContaining("to have replied");
        assertThatThrownBy(() -> assertThat(approval).hasPublishedEvent("x"))
                .hasMessageContaining("to have published");
        assertThatThrownBy(() -> assertThat(approval).hasStepStatus("notify",
                io.mateu.workflow.domain.aggregates.StepExecutionStatus.COMPLETED))
                .hasMessageContaining("to be COMPLETED but it is CREATED");
        assertThatThrownBy(() -> runtime.process("nope")).hasMessageContaining("No process with business key");
        assertThat(approval).doesNotHaveVariable("missing").stepExecutions("approve").hasSize(1);
        assertThat(approval.toString()).contains("approval");
    }
}
