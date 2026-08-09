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
        /**
         * The incoming links of this step, each with its own guard. The way to say "wait for this
         * step, and only by this route if that holds"; the two older fields say which steps to
         * wait for and nothing about the routes.
         *
         * <p>Takes precedence over {@code preconditionStepIds} and {@code preconditionStepId} when
         * declared. Both remain valid — every definition written before this field existed uses
         * them — and a step-level {@code preconditionExpression} still gates the step as a whole,
         * on top of whatever the links say.
         */
        @HiddenInList
        List<Precondition> preconditions,
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
        // @JsonAlias keeps definitions and in-flight step JSON written before the rename (the field
        // was `rollbackable`) deserialising unchanged — the value lives in the persisted stepJson.
        @HiddenInList
        @com.fasterxml.jackson.annotation.JsonAlias("rollbackable")
        boolean compensable,
        @Hidden("!state['compensable']")
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
        JoinType joinType,
        /**
         * Flow-authorization for this step: the scopes and roles the caller must ALL hold for this
         * step to run, evaluated against the process's creation snapshot ({@code AuthorizationContext}).
         * A step-level gate on top of the definition-level one — e.g. an approval step that needs a
         * scope the rest of the flow does not. Empty (the default) means the step adds no restriction.
         * Enforced only when {@code workflow.security.flow-authorization.enabled}.
         */
        @HiddenInList
        List<String> requiredScopes,
        @HiddenInList
        List<String> requiredRoles
) implements Identifiable {

    public Step {
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
    }

    /**
     * The shape this record had before links could carry their own guards, so that every caller
     * written against it — and there are a great many, in this repository and outside it — keeps
     * compiling and keeps meaning what it meant. Preconditions declared this way have no guards.
     */
    public Step(String id, String workflowDefinitionId, StepType type, String name, String description,
                String preconditionStepId, List<String> preconditionStepIds, String preconditionExpression,
                boolean parallel, String topic, String formId, String ruleId,
                String childWorkflowDefinitionId, List<String> outputVariables, long duration,
                String untilVariable, String messageName, String correlationExpression,
                List<String> messageVariables, long timeout, int retries, boolean compensable,
                String compensationStepId, int maxSuccessfulExecutions, JoinType joinType) {
        this(id, workflowDefinitionId, type, name, description, preconditionStepId, preconditionStepIds,
                null, preconditionExpression, parallel, topic, formId, ruleId, childWorkflowDefinitionId,
                outputVariables, duration, untilVariable, messageName, correlationExpression,
                messageVariables, timeout, retries, compensable, compensationStepId,
                maxSuccessfulExecutions, joinType, java.util.List.of(), java.util.List.of());
    }

    /**
     * This step's incoming links, whichever way the definition declared them: {@code
     * preconditions} when present, else the plural {@code preconditionStepIds}, else the singular
     * {@code preconditionStepId}, else none. The older two carry no guard, so their links get
     * none — which is what they have always meant.
     *
     * <p>One accessor for three spellings, so everything downstream — eligibility, the topology
     * warnings, the graph — asks the same question and gets the same answer.
     */
    public List<Precondition> resolvedPreconditions() {
        if (preconditions != null && !preconditions.isEmpty()) {
            return preconditions.stream().filter(p -> p != null && p.stepId() != null).toList();
        }
        if (preconditionStepIds != null && !preconditionStepIds.isEmpty()) {
            return preconditionStepIds.stream().map(id -> new Precondition(id, null)).toList();
        }
        if (preconditionStepId != null && !preconditionStepId.isBlank()) {
            return List.of(new Precondition(preconditionStepId, null));
        }
        return List.of();
    }

    /**
     * The step ids that must have completed before this step can start, without their guards —
     * the shape of the graph rather than its conditions. Used by the topology rules, which are
     * about what connects to what.
     */
    public List<String> preconditionIds() {
        return resolvedPreconditions().stream().map(Precondition::stepId).toList();
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
    /**
     * The moment this step needs the engine's attention once started, or null when it needs none:
     * for a TIMER, its due moment; for anything else, its timeout deadline when one is configured.
     * Materialised on the step execution at start so the scheduler can find due work with an
     * indexed range scan instead of evaluating every live step on every tick.
     *
     * @throws IllegalArgumentException for a TIMER that defines neither a duration nor a
     *                                  resolvable date variable — same contract as
     *                                  {@link #timerDueAt(LocalDateTime, List)}.
     */
    public LocalDateTime deadlineAt(LocalDateTime startedAt, List<Variable> variables) {
        if (StepType.TIMER.equals(type)) {
            return timerDueAt(startedAt, variables);
        }
        return timeout > 0 ? startedAt.plus(timeout, ChronoUnit.MILLIS) : null;
    }

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
