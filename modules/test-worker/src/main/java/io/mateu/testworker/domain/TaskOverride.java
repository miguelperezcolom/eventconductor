package io.mateu.testworker.domain;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.GeneratedValue;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * A canned reply someone saved by hand: "next time this task comes in, do this instead".
 *
 * <p>This record is both the stored rule and the edit form, which is why it is spelled out as
 * separate fields rather than one JSON blob. Changing a duration or flipping an outcome is the
 * common act, and it should not require anyone to write JSON to do it.
 *
 * <p>{@code workflowDefinitionId}, {@code stepId} and {@code taskId} are the matchers, and any of
 * them left blank means "any". A row that leaves all three blank matches every task — occasionally
 * what you want, and worth noticing. The most specific matching row wins, so a blanket row never
 * shadows a precise one.
 *
 * <p>An override applies only to tasks whose process carries no {@code TEST_CONFIG}. A process
 * that states its own scenario gets exactly that scenario, whatever is stored here: a test has to
 * be reproducible from what it says, not from what someone left enabled in this table.
 */
@Style("width: 100%;")
public record TaskOverride(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInCreate
        String id,

        @Section("What it matches")
        String name,
        @Help("Blank matches any workflow definition")
        String workflowDefinitionId,
        @Help("Blank matches any step")
        String stepId,
        @Help("Blank matches any task")
        String taskId,
        @Help("Disabled rows are ignored, and stay here for next time")
        boolean enabled,

        @Section("What it replies")
        Long durationMs,
        Outcome outcome,
        @Help("Sent as an Error log line before the failure. Only used when the outcome is ERROR")
        String reason,
        @Help("Fail this many executions of the step within a process, then succeed")
        Integer failuresBeforeSuccess,
        @Help("More than one is a worker misbehaving on purpose — it tests the engine's idempotency")
        Integer replyTimes,
        @Help("Keep working and reply after the engine has cancelled the task")
        boolean ignoreCancellation,

        @Section("Variables and logs")
        @Colspan(2)
        @MasterDetail(minHeightWhenDetailVisible = "18rem;")
        @Help("Reported back with the reply, and merged into the process")
        List<Variable> variables,

        @Colspan(2)
        @MasterDetail(minHeightWhenDetailVisible = "18rem;")
        @Help("Emitted while the task runs. atMs is milliseconds into the task; blank means at the start")
        List<LogLine> logs) implements Identifiable {

    public TaskOverride {
        variables = variables == null ? List.of() : List.copyOf(variables);
        logs = logs == null ? List.of() : List.copyOf(logs);
    }

    /** How many of the three matchers this row states. The most specific match wins. */
    public int specificity() {
        return (isSet(workflowDefinitionId) ? 1 : 0)
                + (isSet(stepId) ? 1 : 0)
                + (isSet(taskId) ? 1 : 0);
    }

    /** Whether this row applies to the task described. A blank matcher matches anything. */
    public boolean matches(String workflowDefinitionId, String stepId, String taskId) {
        return enabled
                && matches(this.workflowDefinitionId, workflowDefinitionId)
                && matches(this.stepId, stepId)
                && matches(this.taskId, taskId);
    }

    /**
     * This row as a scenario. Anything it leaves blank stays null, so the caller's fallback fills
     * it in — an override that only sets a duration changes only the duration.
     */
    public TaskScenario toScenario() {
        return new TaskScenario(
                durationMs,
                outcome,
                isSet(reason) ? reason : null,
                logs.isEmpty() ? null : logs,
                variables.isEmpty() ? null : variables,
                failuresBeforeSuccess,
                replyTimes,
                ignoreCancellation ? Boolean.TRUE : null);
    }

    @Override
    public String toString() {
        return isSet(name) ? name : "New override";
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean matches(String matcher, String value) {
        return !isSet(matcher) || matcher.equals(value);
    }
}
