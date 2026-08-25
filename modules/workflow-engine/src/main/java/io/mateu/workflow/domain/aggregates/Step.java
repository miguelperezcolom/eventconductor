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
         * them.
         */
        @HiddenInList
        List<Precondition> preconditions,
        /**
         * Deprecated: say it on the link instead ({@link Precondition#expression()}). A condition
         * is about a route into a step, and a step-level one is only the special case where every
         * route asks the same thing — which {@link #resolvedPreconditions()} now expresses by
         * folding this expression into each link, so a guard has exactly one home and one
         * evaluation path.
         *
         * <p>Still read, so every definition written before links could carry guards keeps
         * working. On a step with no links at all — an entry point — it is the only place a
         * condition can go, and it is evaluated as the step's own gate.
         */
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
         * The step to run when THIS step times out — its {@code timeout} elapses with the step still
         * unfinished, after any retries are exhausted. A native alternative to racing the step against
         * a parallel TIMER: instead of the timeout failing the process, flow routes to this step (the
         * step's own on-timeout branch). The timed-out step ends {@code TIMEOUT} (terminal) and, while
         * it carries this, is not counted as a process failure. Compensation cannot express this —
         * compensation only undoes steps that <em>succeeded</em>, and a timed-out step never did, so it
         * has nothing to compensate; this routes forward instead of rolling back.
         */
        @HiddenInList
        @Hidden("state['timeout'] == 0")
        @Lookup(bubble = true)
        String onTimeoutStepId,
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
         * step to run. A step-level gate on top of the definition-level one — e.g. an approval step
         * that needs a scope the rest of the flow does not.
         *
         * <p><b>Declared and not yet enforced.</b> The definition-level requirements are checked when
         * a process is created, where the caller is still on the request; these would be checked when
         * the step runs, which can be a week later on another pod, and that needs the caller's
         * snapshot to have been stored with the process. It has not been. Until then this parses,
         * round-trips and does nothing — stated here rather than left to be discovered.
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
                messageVariables, timeout, retries, compensable, compensationStepId, null,
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
     *
     * <p>A step-level {@code preconditionExpression} is folded in here, ANDed onto every link:
     * "this step only runs if X" is the special case of "every route in requires X", and saying it
     * once, in the links, leaves one kind of guard and one place that evaluates it. The folding is
     * what makes a step-level condition visible to everything that reads the links — the CHOICE
     * branch picker above all, which chooses by the guard on the link and used to be blind to it.
     * A step with no links has nothing to fold into; there the expression stays the step's own
     * gate (see {@code isAnEntryPoint}).
     *
     * <p>The fold carries the meaning across too, not just the text. A step-level expression has
     * always <em>discarded</em> the step when false — the flow did not go this way, and the process
     * may finish around it — while a guard written on a link <em>holds</em> it. That difference is
     * real and is kept, as {@link GuardMode} on the folded link, so no definition changes behaviour
     * by being read through here.
     */
    public List<Precondition> resolvedPreconditions() {
        return foldStepGuardInto(declaredPreconditions());
    }

    /** The links exactly as the definition spells them, before the step-level guard is folded in. */
    private List<Precondition> declaredPreconditions() {
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

    private List<Precondition> foldStepGuardInto(List<Precondition> links) {
        if (preconditionExpression == null || preconditionExpression.isBlank() || links.isEmpty()) {
            return links;
        }
        return links.stream()
                .map(link -> new Precondition(link.stepId(),
                        andGuards(preconditionExpression, link.expression()),
                        // A link that had a guard of its own keeps that guard's meaning: it was
                        // written as something to wait for, and waiting is the conservative
                        // outcome when the two are combined. A link that had none takes the
                        // step-level meaning, which has always been "not this way, carry on".
                        link.hasGuard() ? GuardMode.WAIT : GuardMode.DISCARD))
                .toList();
    }

    /**
     * The step guard ANDed onto a link's own. Both sides are parenthesised: an expression written
     * to stand alone can be anything JEXL parses, and {@code a || b} ANDed unbracketed would bind
     * the wrong way round. A link with no guard of its own simply takes the step's, unwrapped, so
     * the overwhelmingly common case evaluates the very expression the author wrote.
     */
    private static String andGuards(String stepGuard, String linkGuard) {
        if (linkGuard == null || linkGuard.isBlank()) {
            return stepGuard;
        }
        return "(" + stepGuard + ") && (" + linkGuard + ")";
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
