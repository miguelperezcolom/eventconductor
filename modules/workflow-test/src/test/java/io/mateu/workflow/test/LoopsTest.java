package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** repeat and loopBack delays are skipped like any other due moment; forEach instances are mocked one by one. */
@EventConductorTest
class LoopsTest {

    @Test
    void aDelayedRepeatRunsOncePerDelay(WorkflowTestRuntime runtime) {
        runtime.whenTask("stock-check")
                .thenComplete(Map.of("inStock", "no"))
                .thenComplete(Map.of("inStock", "no"))
                .thenComplete(Map.of("inStock", "yes"));

        var poll = runtime.start("stock-poll", "loop-repeat-1", Map.of());
        assertThat(poll).isWaitingAt("poll");

        runtime.advanceTime(Duration.ofMinutes(59));
        assertThat(runtime.invocationsOf("poll")).hasSize(1);

        runtime.advanceTime(Duration.ofHours(2));
        assertThat(poll).isCompleted().hasVariable("inStock", "yes");
        assertThat(poll).stepExecutions("poll").hasSize(3);
        var calls = runtime.invocationsOf("poll");
        assertThat(Duration.between(calls.get(0).at(), calls.get(1).at())).isEqualTo(Duration.ofHours(1));
        assertThat(Duration.between(calls.get(1).at(), calls.get(2).at())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void aDelayedLoopBackRunsItsSectionAgainAfterTheDelay(WorkflowTestRuntime runtime) {
        runtime.whenTask("check-a").thenComplete();
        runtime.whenTask("check-b").thenComplete();
        runtime.whenTask("review").thenComplete(Map.of("again", "yes")).thenComplete(Map.of("again", "no"));

        var review = runtime.start("daily-review", "loop-back-1", Map.of());
        assertThat(runtime.invocationsOf("a")).hasSize(1);

        runtime.advanceTime(Duration.ofHours(5));
        assertThat(review).isRunning();
        assertThat(runtime.invocationsOf("a")).hasSize(1);

        runtime.advanceTime(Duration.ofHours(1));
        assertThat(review).isCompleted();
        assertThat(review).stepExecutions("a").hasSize(2);
        assertThat(review).stepExecutions("review").hasSize(2);
        var a = runtime.invocationsOf("a");
        assertThat(Duration.between(a.get(0).at(), a.get(1).at())).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void eachForEachInstanceIsAnsweredOnItsOwn(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve").thenAnswer(task ->
                TaskOutcome.complete(Map.of("reservationId", "res-" + task.variable("room"))));

        var rooms = runtime.start("rooms", "loop-foreach-1", Map.of("rooms", List.of("101", "102", "103")));

        assertThat(rooms).isCompleted().hasVariable("reservations");
        assertThat(runtime.invocationsOf("reserve")).extracting(TaskInvocation::variables)
                .extracting(variables -> variables.get("room")).containsExactlyInAnyOrder("101", "102", "103");
        assertThat(rooms.variable("reservations")).contains("res-101", "res-102", "res-103");
    }

    @Test
    void parkedForEachInstancesAreCompletedOneAtATime(WorkflowTestRuntime runtime) {
        var rooms = runtime.start("rooms", "loop-foreach-2", Map.of("rooms", List.of("201", "202")));
        assertThat(rooms).isWaitingAt("reserve");

        runtime.completeTask(rooms, "reserve", Map.of("reservationId", "r1"));
        assertThat(rooms).isWaitingAt("reserve");
        runtime.completeTask(rooms, "reserve", Map.of("reservationId", "r2"));

        assertThat(rooms).isCompleted().hasStepStatus("end", StepExecutionStatus.COMPLETED);
    }
}
