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
public record Step(
        @Section(value = "Main", style = "width: 25%;")
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
        @Section(value = "Precondition", style = "width: 25%;")

        //@HiddenInList
        //StepPrecondition precondition,
        @Lookup(bubble = true)
        String preconditionStepId,
        String preconditionExpression,

        @Section(value = "Execution", style = "width: 25%;")
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
        @HiddenInList
        @Hidden("state['type'] != 'TIMER'")
        @JsonDeserialize(using = TimeoutDeserializer.class)
        long duration,
        @HiddenInList
        @Hidden("state['type'] != 'TIMER'")
        String untilVariable,
        @HiddenInList
        @Hidden("state['type'] != 'MESSAGE'")
        String messageName,
        @HiddenInList
        @Hidden("state['type'] != 'MESSAGE'")
        String correlationExpression,
        @Section(value = "Reliability", style = "width: 25%;")
        @JsonDeserialize(using = TimeoutDeserializer.class)
        long timeout,
        @HiddenInList
        int retries,
        @HiddenInList
        boolean rollbackable,
        @Hidden("!state['rollbackable']")
        @Lookup(bubble = true)
        String compensationStepId
) implements Identifiable {

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
