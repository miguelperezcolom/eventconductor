package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Updates of running processes from a test: send one, get its result, assert on how it ended. */
@EventConductorTest
class UpdatesTest {

    @Test
    void anUpdateAnswersWithItsResultAndChangesTheProcess(WorkflowTestRuntime runtime) {
        runtime.whenTask("pack").thenComplete();
        var shipment = runtime.start("shipment", "ship-1", Map.of("address", "Old road 1"));

        var outcome = runtime.update(shipment, "changeAddress", Map.of("address", "New road 2"));

        assertThat(outcome).isCompleted().hasResult("{\"address\": \"New road 2\"}");
        assertThat(outcome.resultValue()).isEqualTo(Map.of("address", "New road 2"));
        assertThat(outcome.updateId()).isNotNull();
        assertThat(outcome.process()).isSameAs(shipment);
        assertThat(shipment).isWaitingAt("await-pickup").hasVariable("address", "New road 2")
                .hasAppliedUpdates("changeAddress");
        assertThat(shipment.updates()).singleElement().satisfies(update -> assertThat(update.result()).contains("New road 2"));
    }

    @Test
    void refusalsAreOutcomesNotExceptions(WorkflowTestRuntime runtime) {
        runtime.whenTask("pack").thenComplete();
        var shipment = runtime.start("shipment", "ship-2", Map.of("address", "Same road 1"));

        assertThat(runtime.update(shipment, "changeAddress", Map.of("address", "Same road 1")))
                .isRefusedWith("already the address");
        assertThat(runtime.update(shipment, "changeAddress", Map.of("address", 7))).isInvalidPayload();
        assertThat(runtime.update(shipment, "changeAddress", "{\"address\": \"x\"}")).isInvalidPayload();
        assertThat(runtime.update(shipment, "nope", null)).hasStatus(UpdateOutcome.Status.UNKNOWN_UPDATE);
        assertThat(shipment).hasAppliedUpdates().hasVariable("address", "Same road 1");
    }

    @Test
    void anUpdateCanMoveTheFlowOnAndAFinishedProcessTakesNoMore(WorkflowTestRuntime runtime) {
        runtime.whenTask("pack").thenComplete();
        runtime.whenTask("deliver").thenComplete();
        var shipment = runtime.start("shipment", "ship-3", Map.of("address", "Road 3"));

        assertThat(runtime.update(shipment, "pickUp", Map.of("courier", "Ana"))).hasResult("\"Ana\"");

        assertThat(shipment).isCompleted().hasPassedSteps("await-pickup", "deliver").hasVariable("courier", "Ana");
        var late = runtime.update(shipment, "changeAddress", Map.of("address", "Road 4"));
        assertThat(late).isNotAccepted().hasErrorContaining("COMPLETED");
        assertThat(late.toString()).contains("NOT_ACCEPTED");
    }

    @Test
    void aKeyMakesARetryTheSameUpdate(WorkflowTestRuntime runtime) {
        runtime.whenTask("pack").thenComplete();
        var shipment = runtime.start("shipment", "ship-4", Map.of("address", "Road 1"));

        var first = runtime.update(shipment, "changeAddress", Map.of("address", "Road 2"), "k");
        var again = runtime.update(shipment, "changeAddress", Map.of("address", "Road 2"), "k");

        assertThat(again.updateId()).isEqualTo(first.updateId());
        assertThat(runtime.update(shipment, "changeAddress", Map.of("address", "Road 3"), "k"))
                .hasStatus(UpdateOutcome.Status.KEY_REUSED);
        assertThat(shipment).hasAppliedUpdates("changeAddress");
    }

    @Test
    void updateWithStartStartsTheProcessOrUpdatesIt(WorkflowTestRuntime runtime) {
        runtime.whenTask("pack").thenComplete();

        var started = runtime.updateWithStart("shipment", "ship-5", Map.of("address", "Road 1"), "changeAddress",
                Map.of("address", "Road 2"));

        assertThat(started).isCompleted().startedTheProcess();
        assertThat(started.process()).isWaitingAt("await-pickup").hasVariable("address", "Road 2");

        var existing = runtime.updateWithStart("shipment", "ship-5", Map.of(), "changeAddress", Map.of("address", "Road 3"));
        assertThat(existing).isCompleted();
        assertThat(existing.started()).isFalse();
        assertThat(existing.process().id()).isEqualTo(started.process().id());

        var refused = runtime.updateWithStart("shipment", "ship-6", Map.of("address", "Road 9"), "changeAddress",
                Map.of("address", "Road 9"));
        assertThat(refused).isRefused();
        assertThat(refused.process()).isNull();
    }

    @Test
    void failedAssertionsSayWhatHappened(WorkflowTestRuntime runtime) {
        runtime.whenTask("pack").thenComplete();
        var shipment = runtime.start("shipment", "ship-7", Map.of("address", "Road 1"));
        var outcome = runtime.update(shipment, "changeAddress", Map.of("address", "Road 2"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertThat(outcome).isRefused())
                .hasMessageContaining("COMPLETED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertThat(outcome).hasResult("{\"address\": \"x\"}"))
                .hasMessageContaining("Road 2");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertThat(outcome).hasErrorContaining("x"))
                .hasMessageContaining("error");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertThat(outcome).startedTheProcess())
                .hasMessageContaining("existing one");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertThat(shipment).hasAppliedUpdates("other"))
                .hasMessageContaining("changeAddress");
        assertThat(outcome).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertThat(outcome).isFailed()).isInstanceOf(AssertionError.class);
    }
}
