package io.mateu.testworker.domain;

import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.uidl.annotations.Multiline;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.dtos.Variable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One task this worker was asked to do, and what it did about it.
 *
 * <p>Every field is read-only: this is the record of what happened, and editing history is how you
 * end up debugging a run that never occurred. Changing what happens <em>next</em> is a
 * {@link TaskOverride}, which the Received tasks page can create from a row.
 *
 * <p>{@code source} is the field people come here for. When a run surprises you, the first
 * question is whether the reply came from the scenario the test wrote or from an override left
 * enabled in the table, and this answers it without anyone having to reason about precedence.
 */
@Style("width: 100%;")
public record ReceivedTask(
        @ReadOnly String id,

        @Section("Task")
        @ReadOnly String processId,
        @ReadOnly String workflowDefinitionId,
        @ReadOnly String stepId,
        @ReadOnly String taskId,
        @ReadOnly LocalDateTime receivedAt,
        @ReadOnly Integer attempt,

        @Section("What was played")
        @ReadOnly ScenarioSource source,
        @ReadOnly String matchedBy,
        @ReadOnly Outcome outcome,
        @ReadOnly Long durationMs,
        @ReadOnly LocalDateTime repliedAt,
        @ReadOnly String note,

        @Section("Variables")
        @Colspan(2)
        @MasterDetail(minHeightWhenDetailVisible = "18rem;")
        @ReadOnly List<Variable> requestVariables,

        @Colspan(2)
        @Multiline
        @ReadOnly String scenarioJson) implements Identifiable {

    public ReceivedTask {
        requestVariables = requestVariables == null ? List.of() : List.copyOf(requestVariables);
    }

    /** The same row once the reply has gone out. */
    public ReceivedTask repliedWith(Outcome played, LocalDateTime at, String noteOrNull) {
        return new ReceivedTask(id, processId, workflowDefinitionId, stepId, taskId, receivedAt,
                attempt, source, matchedBy, played, durationMs, at,
                noteOrNull != null ? noteOrNull : note, requestVariables, scenarioJson);
    }

    @Override
    public String toString() {
        return taskId + " · " + processId;
    }
}
