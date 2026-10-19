package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * USER_TASK assignment and escalation, end to end on the engine with the kit's clock: who a task is
 * for rendered from the process (an iteration's item included), escalations firing as time is skipped
 * — a reassignment, a notification with its event, and an escalateTo routing the process on.
 */
@EventConductorTest
class HumanTaskAssignmentTest {

    @Autowired
    WorkflowTestRuntime runtime;

    private static HumanTaskAssignment assignmentOf(TaskInvocation invocation) {
        return HumanTaskAssignment.fromJson(invocation.variable(HumanTaskAssignment.VARIABLE));
    }

    private static StepExecution only(TestProcess process, String stepId) {
        return process.stepExecutions(stepId).getFirst();
    }

    @Test
    void theTaskIsForWhoeverTheProcessSays() {
        var expense = runtime.start("expense-approval", "expense-1",
                Map.of("manager", "bob", "region", "emea", "amount", 1500, "expenseId", "E-1"));

        assertThat(expense).isWaitingAt("approve");
        var assignment = assignmentOf(runtime.invocationsOf("approve").getFirst());
        assertThat(assignment.assignee()).isEqualTo("bob");
        assertThat(assignment.candidateGroups()).containsExactly("finance-emea");
        assertThat(assignment.priority()).isEqualTo(90);
        assertThat(assignment.businessKey()).isEqualTo("expense-1");
        assertThat(Instant.parse(assignment.dueDate()))
                .isEqualTo(runtime.now().atZone(EngineClock.zone()).toInstant().plus(Duration.ofDays(2)));

        runtime.completeUserTask(expense, "approve", Map.of("approved", true));

        assertThat(expense).isCompleted().hasNotPassedSteps("director-review");
        assertThat(only(expense, "approve").escalationsFired()).isZero();
    }

    @Test
    void escalationsFireAsTimePassesAndTheLastOneRoutesTheProcess() {
        var expense = runtime.start("expense-approval", "expense-2",
                Map.of("manager", "bob", "region", "emea", "amount", 200, "expenseId", "E-2"));

        runtime.advanceTime(Duration.ofHours(47));
        assertThat(only(expense, "approve").escalationsFired()).isZero();

        // 48 hours: reassigned to the finance managers, and the escalation is announced.
        runtime.advanceTime(Duration.ofHours(1));
        var approve = only(expense, "approve");
        assertThat(approve.escalationsFired()).isEqualTo(1);
        assertThat(approve.assignment().assignee()).isNull();
        assertThat(approve.assignment().candidateGroups()).containsExactly("finance-managers");
        // The task keeps its urgency and its due date through a reassignment.
        assertThat(approve.assignment().priority()).isEqualTo(40);
        assertThat(expense).isWaitingAt("approve").hasPublishedEvent("com.acme.task.escalated");
        assertThat(runtime.publishedEvents(expense)).singleElement().satisfies(event -> {
            assertThat(event.data()).contains("\"level\":1").contains("\"expense\":\"E-2\"");
            assertThat(event.destination()).isEqualTo("notifications");
        });

        // Day 4: a notification; nothing changes on the task.
        runtime.advanceTime(Duration.ofDays(2));
        assertThat(only(expense, "approve").escalationsFired()).isEqualTo(2);
        assertThat(expense).isWaitingAt("approve");

        // Day 5: escalated to the director's review — the approval is closed, the process goes on there.
        runtime.advanceTime(Duration.ofDays(1));
        assertThat(expense).hasStepStatus("approve", StepExecutionStatus.TIMEOUT).isWaitingAt("director-review");
        assertThat(assignmentOf(runtime.invocationsOf("director-review").getFirst()).candidateGroups())
                .containsExactly("directors");

        runtime.completeUserTask(expense, "director-review", Map.of("approved", false));
        assertThat(expense).isCompleted().hasVariable("approved", false);
    }

    @Test
    void aTaskCompletedInTimeIsNeverEscalated() {
        var expense = runtime.start("expense-approval", "expense-3",
                Map.of("manager", "bob", "region", "emea", "amount", 200, "expenseId", "E-3"));

        runtime.advanceTime(Duration.ofHours(24));
        runtime.completeUserTask(expense, "approve", Map.of("approved", true));
        runtime.advanceTime(Duration.ofDays(10));

        assertThat(expense).isCompleted();
        assertThat(only(expense, "approve").escalationsFired()).isZero();
        assertThat(runtime.publishedEvents(expense)).isEmpty();
    }

    @Test
    void eachIterationOfAForEachIsATaskForItsOwnPersonAndEscalatesOnItsOwn() {
        var order = runtime.start("line-approvals", "order-1", Map.of("lines",
                "[{\"owner\": \"ana\", \"amount\": 50, \"team\": \"a\"},"
                        + " {\"owner\": \"ben\", \"amount\": 500, \"team\": \"b\"}]"));

        var tasks = runtime.invocationsOf("approve-line").stream().map(HumanTaskAssignmentTest::assignmentOf)
                .sorted(Comparator.comparing(HumanTaskAssignment::assignee)).toList();
        assertThat(tasks).extracting(HumanTaskAssignment::assignee).containsExactly("ana", "ben");
        assertThat(tasks).extracting(HumanTaskAssignment::priority).containsExactly(20, 80);

        runtime.advanceTime(Duration.ofDays(1));

        var iterations = order.stepExecutions("approve-line").stream().filter(StepExecution::isIteration).toList();
        assertThat(iterations).hasSize(2).allSatisfy(iteration -> assertThat(iteration.escalationsFired()).isEqualTo(1));
        assertThat(iterations).extracting(iteration -> iteration.assignment().candidateGroups().getFirst())
                .containsExactlyInAnyOrder("a-leads", "b-leads");

        runtime.completeUserTask(order, "approve-line", Map.of("ok", true));
        runtime.completeUserTask(order, "approve-line", Map.of("ok", true));
        assertThat(order).isCompleted();
    }
}
