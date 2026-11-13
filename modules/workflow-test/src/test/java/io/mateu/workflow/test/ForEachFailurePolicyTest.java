package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** forEach onIterationFailure: what one failed item does to the others. */
@EventConductorTest
class ForEachFailurePolicyTest {

    private static void roomTwoIsFull(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenAnswer(task -> "2".equals(task.variable("room"))
                ? TaskOutcome.fail("FULL", false)
                : TaskOutcome.complete(Map.of("reservationId", "R" + task.variable("room"))));
        runtime.whenTask("release-room").thenComplete();
    }

    @Test
    void continueCompletesAndTellsWhichItemsFailed(WorkflowTestRuntime runtime) throws Exception {
        roomTwoIsFull(runtime);

        var run = runtime.start("rooms-continue", "policy-continue-1", Map.of("rooms", List.of("1", "2", "3")));

        assertThat(run).isCompleted();
        assertThat(runtime.invocationsOf("reserve")).hasSize(3);
        assertThat(runtime.invocationsOf("release")).isEmpty();
        var output = new ObjectMapper().readValue(run.variable("reservations"), List.class);
        assertThat(output).containsExactly(
                Map.of("status", "COMPLETED", "value", "R1"),
                Map.of("status", "FAILED", "error", "ERROR"),
                Map.of("status", "COMPLETED", "value", "R3"));
    }

    @Test
    void compensateAllWaitsForEveryItemThenUndoesTheOnesThatCompleted(WorkflowTestRuntime runtime) {
        roomTwoIsFull(runtime);

        var run = runtime.start("rooms-compensate-all", "policy-compensate-1", Map.of("rooms", List.of("1", "2", "3")));

        assertThat(run).isCompensated();
        assertThat(runtime.invocationsOf("reserve")).hasSize(3);
        assertThat(runtime.invocationsOf("release")).extracting(release -> release.variable("reservationId"))
                .containsExactlyInAnyOrder("R1", "R3");
    }

    @Test
    void compensateAllWithoutFailuresCompletesAsUsual(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenAnswer(task ->
                TaskOutcome.complete(Map.of("reservationId", "R" + task.variable("room"))));

        var run = runtime.start("rooms-compensate-all", "policy-compensate-2", Map.of("rooms", List.of("1", "2", "3")));

        assertThat(run).isCompleted();
        assertThat(run.variable("reservations")).isEqualTo("[\"R1\",\"R2\",\"R3\"]");
        assertThat(runtime.invocationsOf("release")).isEmpty();
    }

    @Test
    void failFastIsStillTheDefault(WorkflowTestRuntime runtime) {
        // rooms.yaml declares no onIterationFailure: one failure fails the step, as it always did.
        runtime.whenTask("reserve").thenAnswer(task -> "2".equals(task.variable("room"))
                ? TaskOutcome.fail("FULL", false)
                : TaskOutcome.complete(Map.of("reservationId", "R" + task.variable("room"))));

        var run = runtime.start("rooms", "policy-default-1", Map.of("rooms", List.of("1", "2", "3")));

        assertThat(run.status()).isIn(io.mateu.workflow.domain.aggregates.ProcessStatus.ERROR,
                io.mateu.workflow.domain.aggregates.ProcessStatus.COMPENSATED);
    }
}
