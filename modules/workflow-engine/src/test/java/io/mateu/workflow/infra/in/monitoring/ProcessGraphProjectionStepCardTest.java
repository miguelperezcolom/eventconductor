package io.mateu.workflow.infra.in.monitoring;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a step's node and card say on the process diagram: a loop's step whose later pass was
 * cancelled, and a USER_TASK's card without the engine's own variables.
 */
class ProcessGraphProjectionStepCardTest {

    private static final String FIX = """
            {"id":"fix-issues","type":"ACTION","name":"Fix","topic":"t","loopBack":{"to":"inspect-room","maxIterations":3}}""";
    private static final String APPROVE = """
            {"id":"approve","type":"USER_TASK","name":"Approve","formId":"booking-approval"}""";

    private static WorkflowDefinition definition(String... steps) {
        return pojoFromJson("{\"id\":\"prepare-room\",\"name\":\"Prepare\",\"version\":1,\"steps\":["
                + String.join(",", steps) + "]}", WorkflowDefinition.class);
    }

    private static Process process() {
        return Process.builder().id("p-1").workflowDefinitionId("prepare-room").variables(new ArrayList<>()).build();
    }

    private static StepExecution.StepExecutionBuilder exec(String id, String stepId, String stepJson, StepExecutionStatus status) {
        return StepExecution.builder().id(id).processId("p-1").workflowDefinitionId("prepare-room")
                .stepId(stepId).stepJson(stepJson).status(status).variables(List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(ProcessGraphProjection.Graph graph, String stepId) {
        return (Map<String, Object>) graph.overlay().get(stepId);
    }

    @Test
    void aLoopStepThatRanAndWhoseNextPassWasCancelledReadsAsCompletedWithTheCancelledPassNoted() {
        var ran = exec("f0", "fix-issues", FIX, StepExecutionStatus.COMPLETED).iteration(0).superseded(true)
                .startedAt(LocalDateTime.parse("2026-11-14T10:00:00"))
                .finishedAt(LocalDateTime.parse("2026-11-14T10:00:01")).build();
        var cancelled = exec("f1", "fix-issues", FIX, StepExecutionStatus.CANCELLED).iteration(1)
                .finishedAt(LocalDateTime.parse("2026-11-14T10:00:05")).build();

        var graph = ProcessGraphProjection.project(definition(FIX), process(), List.of(ran, cancelled), List.of());

        assertThat(entry(graph, "fix-issues"))
                .containsEntry("state", "COMPLETED")
                .containsEntry("cancelledPass", 2)
                .containsEntry("startedAt", "2026-11-14T10:00")
                .containsEntry("order", 1);
        assertThat((String) entry(graph, "fix-issues").get("reason")).contains("run 2 was cancelled");
    }

    @Test
    void aCancelledStepThatNeverRanStaysCancelled() {
        var cancelled = exec("f0", "fix-issues", FIX, StepExecutionStatus.CANCELLED).build();

        var graph = ProcessGraphProjection.project(definition(FIX), process(), List.of(cancelled), List.of());

        assertThat(entry(graph, "fix-issues")).containsEntry("state", "CANCELLED").doesNotContainKey("cancelledPass");
    }

    @Test
    void anEarlierPassARestartDiscardedDoesNotCount() {
        var discarded = exec("f0", "fix-issues", FIX, StepExecutionStatus.COMPLETED).iteration(0).superseded(true)
                .loopDecision(StepExecution.LOOP_DISCARDED).build();
        var cancelled = exec("f1", "fix-issues", FIX, StepExecutionStatus.CANCELLED).iteration(1).build();

        var graph = ProcessGraphProjection.project(definition(FIX), process(), List.of(discarded, cancelled), List.of());

        assertThat(entry(graph, "fix-issues")).containsEntry("state", "CANCELLED");
    }

    @Test
    void aUserTaskCardShowsItsFormAndAssigneeNotTheEnginesVariables() {
        var assignment = new HumanTaskAssignment("mia", List.of("leo"), List.of("managers"), null, null, null, "BOOKING-7");
        var task = exec("a0", "approve", APPROVE, StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.parse("2026-11-14T10:00:00"))
                .variables(List.of(new Variable("guest", "Grace"), new Variable("formId", "booking-approval"),
                        new Variable(HumanTaskAssignment.VARIABLE, assignment.toJson()),
                        new Variable("__escalations", "[]")))
                .build();

        var graph = ProcessGraphProjection.project(definition(APPROVE), process(), List.of(task), List.of());

        var entry = entry(graph, "approve");
        assertThat(entry).containsEntry("form", "booking-approval").containsEntry("assignee", "mia")
                .containsEntry("candidates", "leo, group managers");
        assertThat(entry.get("variables").toString()).contains("guest").doesNotContain("__task")
                .doesNotContain("__escalations").doesNotContain("formId");
    }

    @Test
    void aVariableNamedFormIdOnAnyOtherStepIsTheProcesssOwn() {
        assertThat(ProcessGraphProjection.isEngineInternal("formId", false)).isFalse();
        assertThat(ProcessGraphProjection.isEngineInternal("formId", true)).isTrue();
        assertThat(ProcessGraphProjection.isEngineInternal("__http", false)).isTrue();
    }

    @Test
    void theAttemptsCellSaysWhichAttemptOfHowMany() {
        assertThat(Step.attempts(0, 0, false)).isNull();
        assertThat(Step.attempts(0, 0, true)).isEqualTo("1");
        assertThat(Step.attempts(0, 2, true)).isEqualTo("1/3");
        assertThat(Step.attempts(2, 2, true)).isEqualTo("3/3");
        // Waiting out a retry backoff: not started again, but it has been tried.
        assertThat(Step.attempts(1, 2, false)).isEqualTo("2/3");
    }

    @Test
    void aRetriedExecutionReadsItsRetriesFromTheStepItRan() {
        var se = exec("h0", "call", """
                {"id":"call","type":"ACTION","name":"Call","topic":"t","retries":2}""", StepExecutionStatus.ERROR)
                .attemptCount(2).startedAt(LocalDateTime.parse("2026-11-14T10:00:00")).build();

        assertThat(Step.attempts(se)).isEqualTo("3/3");
    }
}
