package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.With;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@With
@FoldedLayout
@Zones({
        @Zone(name = "main", width = "25%"),
        @Zone(name = "precondition", width = "25%"),
        @Zone(name = "execution", width = "25%"),
        @Zone(name = "reliability", width = "25%")
})
public record Step(
        @Section(value = "Main", zone = "main")
        @NotEmpty
        String id,
        @Hidden
        String workflowDefinitionId,
        @NotNull
        StepType type,
        @NotEmpty
        String name,
        @HiddenInList
        @Stereotype(FieldStereotype.textarea)
        String description,
        @Section(value = "Precondition", zone = "precondition")

        //@HiddenInList
        //StepPrecondition precondition,
        @Lookup(bubble = true)
        String preconditionStepId,
        /**
         * IDs of the steps that must ALL have completed before this one can start. Preferred
         * over the singular {@code preconditionStepId}, which remains a valid way to declare a
         * single precondition. See {@link #preconditions()}.
         */
        @HiddenInList
        List<String> preconditionStepIds,
        String preconditionExpression,

        /**
         * Deprecated and ignored: parallelism is expressed with FORK/JOIN and preconditions —
         * every step whose preconditions are met starts concurrently. Kept only so persisted
         * stepJson and old definition files keep deserializing.
         */
        @Section(value = "Execution", zone = "execution")
        boolean parallel,
        @HiddenInList
        @Hidden("state['type'] != 'ACTION'")
        String topic,
        @HiddenInList
        @Hidden("state['type'] != 'USER_TASK'")
        String formId,
        @HiddenInList
        @Hidden("state['type'] != 'RULE'")
        String ruleId,
        @HiddenInList
        @Hidden("state['type'] != 'PROCESS'")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String childWorkflowDefinitionId,
        /**
         * PROCESS only: names of the child process variables copied back into the parent when
         * the child completes. Empty or absent means none.
         */
        @HiddenInList
        @Hidden("state['type'] != 'PROCESS'")
        List<String> outputVariables,
        @HiddenInList
        @Hidden("state['type'] != 'TIMER'")
        @JsonDeserialize(using = TimeoutDeserializer.class)
        long duration,
        @HiddenInList
        @Hidden("state['type'] != 'TIMER'")
        String untilVariable,
        @HiddenInList
        @Hidden("state['type'] != 'WAIT_FOR_MESSAGE' && state['type'] != 'SEND_MESSAGE'")
        String messageName,
        @HiddenInList
        @Hidden("state['type'] != 'WAIT_FOR_MESSAGE' && state['type'] != 'SEND_MESSAGE'")
        String correlationExpression,
        /**
         * SEND_MESSAGE only: names of the process variables to carry in the message. Empty or
         * absent means the message carries no variables — process state is never sent implicitly.
         */
        @HiddenInList
        @Hidden("state['type'] != 'SEND_MESSAGE'")
        List<String> messageVariables,
        @Section(value = "Reliability", zone = "reliability")
        @JsonDeserialize(using = TimeoutDeserializer.class)
        long timeout,
        @HiddenInList
        int retries,
        @HiddenInList
        boolean rollbackable,
        @Hidden("!state['rollbackable']")
        @Lookup(bubble = true)
        String compensationStepId,
        /**
         * Cap on how many times this step may SUCCESSFULLY run within one process instance — a
         * runtime backstop against runaway loops. 0 inherits the workflow's
         * {@code defaultMaxStepExecutions}; both 0 = unbounded. (Design metadata today: the engine
         * runs each step once; enforced when step re-execution lands.)
         */
        @HiddenInList
        int maxSuccessfulExecutions,
        /**
         * JOIN only: whether the join waits for ALL incoming branches ({@link JoinType#AND}, the
         * default) or proceeds on ANY one ({@link JoinType#XOR}). Null is treated as AND.
         */
        @HiddenInList
        @Hidden("state['type'] != 'JOIN'")
        JoinType joinType
) implements Identifiable {

    /**
     * The step ids that must ALL have completed before this step can start: the plural
     * {@code preconditionStepIds} when declared, else the singular {@code preconditionStepId},
     * else none.
     */
    public List<String> preconditions() {
        if (preconditionStepIds != null && !preconditionStepIds.isEmpty()) {
            return preconditionStepIds;
        }
        if (preconditionStepId != null && !preconditionStepId.isBlank()) {
            return List.of(preconditionStepId);
        }
        return List.of();
    }

    /**
     * Moment a TIMER step is due, derived only from persisted state (the step definition,
     * the start time and the variable snapshot) so it survives restarts. {@code untilVariable}
     * — the name of a process variable holding an ISO 8601 date or date-time — takes
     * precedence over {@code duration} (milliseconds counted from {@code startedAt}).
     *
     * @throws IllegalArgumentException if the timer is misconfigured, the referenced
     *         variable is absent or its value cannot be parsed.
     */
    public LocalDateTime timerDueAt(LocalDateTime startedAt, List<Variable> variables) {
        if (untilVariable != null && !untilVariable.isBlank()) {
            var value = variables == null ? null : variables.stream()
                    .filter(variable -> untilVariable.equals(variable.name()))
                    .map(Variable::value)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Timer step '" + id + "' expects a date in variable '" + untilVariable
                                + "' but the process does not carry it.");
            }
            return parseDateOrDateTime(value.trim());
        }
        if (duration > 0) {
            return startedAt.plus(duration, ChronoUnit.MILLIS);
        }
        throw new IllegalArgumentException(
                "Timer step '" + id + "' defines neither a duration nor an untilVariable.");
    }

    private static LocalDateTime parseDateOrDateTime(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Cannot parse '" + text + "' as an ISO 8601 date or date-time.", e);
        }
    }
}
