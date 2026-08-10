package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Status;
import io.mateu.uidl.data.*;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.uidl.interfaces.LookupOptionsSupplier;
import io.mateu.uidl.interfaces.SearchableText;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.usecases.lifecycle.DisableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.EnableWorkflowDefinitionUseCase;
import io.mateu.workflow.infra.in.ui.WorkflowHome;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@FormLayout(columns = 5)
@Style(StyleConstants.FULL_WIDTH_WITH_PADDING)
public record WorkflowDefinition(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInEditor
        String id,
        @NotEmpty
        String name,
        @Hidden
        int version,
        @Colspan(2)
        @Stereotype(FieldStereotype.textarea)
        String description,
        boolean limitConcurrentExecutions,
        @Min(0)
        @Hidden("!state.limitConcurrentExecutions")
        int maxConcurrentExecutions,
        @Hidden("!state.limitConcurrentExecutions")
        boolean enqueueOnLimit,
        String cronExpression,
        @Min(0)
        int defaultMaxStepExecutions,
        @Colspan(5)
        @DetailFormCustomisation(position = FormPosition.modalRight, style = "display: block; min-width: 70rem;")
        List<Step> steps,
        // Runtime pause flag: while true, all this definition's processes are held and new
        // instances are created already PAUSED (creation itself is still accepted, cron
        // included). Never in the .ec — toggled through Pause/ResumeWorkflowUseCase.
        @Hidden
        boolean paused,
        /**
         * What this workflow's own definition declares — ACTIVE, DISABLED or ARCHIVED — carried in
         * the file as `status`.
         *
         * <p>It is a floor, not a suggestion: the runtime can take a workflow out of service that
         * the file allows, and cannot put one into service that the file does not. That asymmetry
         * is the point — the file is in version control and reviewed, the toggle is a button — and
         * it is what lets a definition live in the repository without being live.
         */
        @Hidden
        @com.fasterxml.jackson.annotation.JsonProperty("status")
        WorkflowStatus declaredStatus,
        /**
         * What an operator (or the git-import prune, which archives a definition that disappeared
         * from its repository) has decided. Engine state, never file syntax, and preserved across
         * imports rather than overwritten by them.
         */
        @Hidden
        @com.fasterxml.jackson.annotation.JsonIgnore
        WorkflowStatus runtimeStatus,
        /**
         * Flow-authorization: the scopes and roles a caller must hold to CREATE a process of this
         * definition. Evaluated against the caller's snapshot at creation (see {@code AuthorizationContext}),
         * requires-all. Empty (the default) means open — no restriction. Enforced only when
         * {@code workflow.security.flow-authorization.enabled}.
         */
        List<String> requiredScopes,
        List<String> requiredRoles,
        /**
         * Cap on how many step executions one process instance of this definition may ever hold,
         * runtime injections included (see {@code InjectStepsUseCase}). A per-definition override
         * of the engine-wide {@code workflow.dynamic.max-steps-per-process}: 0 — the default —
         * falls back to that global default; any positive value takes precedence over it. Additive:
         * a definition file (or a row) written before this field existed deserialises to 0.
         */
        @Min(0)
        int maxSteps
) implements Identifiable, SearchableText, LookupOptionsSupplier, VisibilitySupplier {

    /**
     * Neither status is ever null inside the record: absent in a file, absent in a row written
     * before the column existed, and absent from a copy made by an older caller all mean active.
     * Defaulting in the accessors instead left the components themselves null, so two definitions
     * that mean the same thing were not equal — which is how a definition stopped round-tripping
     * through its own exporter.
     */
    public WorkflowDefinition {
        declaredStatus = declaredStatus == null ? WorkflowStatus.ACTIVE : declaredStatus;
        runtimeStatus = runtimeStatus == null ? WorkflowStatus.ACTIVE : runtimeStatus;
        // Absent in a file, in an old row, or in a copy from an older caller all mean "no requirement".
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
    }

    /** Creation without any lifecycle state: definitions start unpaused and active. */
    public WorkflowDefinition(String id, String name, int version, String description,
                              boolean limitConcurrentExecutions, int maxConcurrentExecutions,
                              boolean enqueueOnLimit, String cronExpression,
                              int defaultMaxStepExecutions, List<Step> steps) {
        this(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, false, WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE, List.of(), List.of(), 0);
    }

    /**
     * The shape this record had while the state was two booleans, so callers written against it
     * keep compiling and keep meaning what they meant.
     */
    public WorkflowDefinition(String id, String name, int version, String description,
                              boolean limitConcurrentExecutions, int maxConcurrentExecutions,
                              boolean enqueueOnLimit, String cronExpression,
                              int defaultMaxStepExecutions, List<Step> steps,
                              boolean paused, boolean disabled, boolean archived) {
        this(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, WorkflowStatus.ACTIVE,
                WorkflowStatus.of(null, disabled, archived), List.of(), List.of(), 0);
    }

    /** The canonical shape before flow-authorization requirements existed — callers that build a
     *  definition with explicit statuses keep compiling and get no requirements by default. */
    public WorkflowDefinition(String id, String name, int version, String description,
                              boolean limitConcurrentExecutions, int maxConcurrentExecutions,
                              boolean enqueueOnLimit, String cronExpression,
                              int defaultMaxStepExecutions, List<Step> steps,
                              boolean paused, WorkflowStatus declaredStatus, WorkflowStatus runtimeStatus) {
        this(id, name, version, description, limitConcurrentExecutions, maxConcurrentExecutions,
                enqueueOnLimit, cronExpression, defaultMaxStepExecutions, steps, paused,
                declaredStatus, runtimeStatus, List.of(), List.of(), 0);
    }

    /**
     * Annotated here as well as on the components: writing an accessor by hand replaces the
     * one a record generates, and the component's annotations do not follow it — which is how
     * `runtimeStatus`, engine state that has no business in a definition file, ended up being
     * serialised into one.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("status")
    public WorkflowStatus declaredStatus() {
        return declaredStatus;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public WorkflowStatus runtimeStatus() {
        return runtimeStatus;
    }

    /**
     * The answer the cron scheduler and process creation ask: the stricter of what the file
     * declares and what the runtime has decided.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public WorkflowStatus status() {
        return declaredStatus().and(runtimeStatus());
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean disabled() {
        return status() != WorkflowStatus.ACTIVE;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean archived() {
        return status() == WorkflowStatus.ARCHIVED;
    }

    /** Returns a copy with a different runtime pause flag. */
    public WorkflowDefinition withPaused(boolean newPaused) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, newPaused, declaredStatus, runtimeStatus, requiredScopes, requiredRoles, maxSteps);
    }

    /** Returns a copy with a different runtime status — what an operator decided. */
    public WorkflowDefinition withRuntimeStatus(WorkflowStatus newStatus) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, declaredStatus, newStatus, requiredScopes, requiredRoles, maxSteps);
    }

    /** Returns a copy with a different declared status — what the definition file says. */
    public WorkflowDefinition withDeclaredStatus(WorkflowStatus newStatus) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, newStatus, runtimeStatus, requiredScopes, requiredRoles, maxSteps);
    }

    /** Kept for callers written against the boolean API. */
    public WorkflowDefinition withDisabled(boolean newDisabled) {
        return withRuntimeStatus(newDisabled ? WorkflowStatus.DISABLED : WorkflowStatus.ACTIVE);
    }

    public WorkflowDefinition withArchived(boolean newArchived) {
        return withRuntimeStatus(newArchived ? WorkflowStatus.ARCHIVED
                : (runtimeStatus() == WorkflowStatus.ARCHIVED ? WorkflowStatus.ACTIVE : runtimeStatus()));
    }

    /** Returns a copy carrying the runtime state of {@code existing}, keeping this one's declaration. */
    public WorkflowDefinition withRuntimeStateOf(WorkflowDefinition existing) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, existing.paused(), declaredStatus, existing.runtimeStatus(), requiredScopes, requiredRoles, maxSteps);
    }

    /** Returns a copy carrying a different step list, every other field unchanged. */
    public WorkflowDefinition withSteps(List<Step> newSteps) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                newSteps, paused, declaredStatus, runtimeStatus, requiredScopes, requiredRoles, maxSteps);
    }

    /** Returns a copy carrying the given per-process step cap ({@code maxSteps}). */
    public WorkflowDefinition withMaxSteps(int newMaxSteps) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, declaredStatus, runtimeStatus, requiredScopes, requiredRoles, newMaxSteps);
    }

    /** Returns a copy carrying the given (engine-assigned) version number. */
    public WorkflowDefinition withVersion(int newVersion) {
        return new WorkflowDefinition(id, name, newVersion, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, declaredStatus(), runtimeStatus(), requiredScopes, requiredRoles, maxSteps);
    }

    // ── Detail-view lifecycle buttons (conditional on state via VisibilitySupplier) ──

    /**
     * Definitions are authored as {@code .ec} files (edited with the IDE plugins), not in the UI:
     * the built-in {@code edit} action is always hidden. Only the runtime toggles remain —
     * {@code disable} when enabled, {@code enable} when disabled.
     */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "edit" -> true;
            case "disable" -> disabled();
            case "enable" -> !disabled();
            default -> false;
        };
    }

    @Toolbar
    public UICommand disable(HttpRequest httpRequest) {
        MateuBeanProvider.getBean(DisableWorkflowDefinitionUseCase.class).handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    @Toolbar
    public UICommand enable(HttpRequest httpRequest) {
        MateuBeanProvider.getBean(EnableWorkflowDefinitionUseCase.class).handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    private static UICommand navigateToDefinition(String definitionId, HttpRequest httpRequest) {
        return UICommand.builder()
                .type(UICommandType.DispatchEvent)
                .data(new DispatchEventData(
                        "navigation-requested",
                        NavigationRequestedPayload.builder()
                                .route("/workflow/definitions/" + definitionId)
                                .consumedRoute("")
                                .baseUrl(httpRequest.getBaseUrl())
                                .uriPrefix("")
                                .serverSideType(WorkflowHome.class.getName())
                                .build()))
                .build();
    }

    @Override
    public String toString() {
        return id != null?name:"New workflow definition";
    }

    @Override
    public String searchableText() {
        return name + " " + description;
    }

    /**
     * Checks domain invariants that cannot be expressed as field-level annotations.
     *
     * @throws IllegalStateException if any invariant is violated.
     */
    public void checkInvariants() {
        if (steps == null) return;
        // Reachability below is judged against these: a compensation is declared on the step it
        // undoes and started by the rollback pipeline, so it needs no way in of its own.
        var compensationTargets = compensationTargets();
        // At most one START: a flow has a single entry point (or enters via WAIT_FOR_MESSAGE).
        // Multiple END steps are fine — a flow may finish through several distinct outcomes.
        long startCount = steps.stream().filter(s -> StepType.START.equals(s.type())).count();
        if (startCount > 1) {
            throw new IllegalStateException(
                    "A workflow can have at most one START step, but found " + startCount + ".");
        }
        var stepIds = new java.util.HashSet<String>();
        for (var step : steps) {
            if (step.id() == null) continue;
            if (!stepIds.add(step.id())) {
                throw new IllegalStateException(
                        "Duplicate step id '" + step.id() + "'.");
            }
            if (step.preconditionIds().contains(step.id())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' cannot have itself as a precondition.");
            }
            if (step.id().equals(step.compensationStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' cannot have itself as a compensation step.");
            }
            if (StepType.START.equals(step.type()) && !step.preconditionIds().isEmpty()) {
                throw new IllegalStateException(
                        "START step '" + step.id() + "' cannot have preconditions.");
            }
            if (!StepType.START.equals(step.type()) && !StepType.WAIT_FOR_MESSAGE.equals(step.type())
                    && step.preconditionIds().isEmpty() && !compensationTargets.contains(step.id())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' has no preconditions and is not a START, a"
                                + " WAIT_FOR_MESSAGE or another step's compensation — nothing"
                                + " would ever start it.");
            }
            if (StepType.PROCESS.equals(step.type())) {
                if (step.childWorkflowDefinitionId() == null || step.childWorkflowDefinitionId().isBlank()) {
                    throw new IllegalStateException(
                            "Process step '" + step.id() + "' must define a childWorkflowDefinitionId.");
                }
                if (step.childWorkflowDefinitionId().equals(id)) {
                    throw new IllegalStateException(
                            "Process step '" + step.id() + "' cannot start this workflow itself as its child.");
                }
            }
            if (StepType.TIMER.equals(step.type()) && step.duration() <= 0
                    && (step.untilVariable() == null || step.untilVariable().isBlank())) {
                throw new IllegalStateException(
                        "Timer step '" + step.id() + "' must define a duration or an untilVariable.");
            }
            if (StepType.WAIT_FOR_MESSAGE.equals(step.type()) || StepType.SEND_MESSAGE.equals(step.type())) {
                if (step.messageName() == null || step.messageName().isBlank()) {
                    throw new IllegalStateException(
                            "Message step '" + step.id() + "' must define a messageName.");
                }
                if (step.correlationExpression() == null || step.correlationExpression().isBlank()) {
                    throw new IllegalStateException(
                            "Message step '" + step.id() + "' must define a correlationExpression.");
                }
            }
        }
        for (var step : steps) {
            if (step.id() == null) continue;
            for (var preconditionStepId : step.preconditionIds()) {
                if (!stepIds.contains(preconditionStepId)) {
                    throw new IllegalStateException(
                            "Step '" + step.id() + "' references unknown precondition step '"
                                    + preconditionStepId + "'.");
                }
            }
            if (step.compensationStepId() != null && !step.compensationStepId().isBlank()
                    && !stepIds.contains(step.compensationStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' references unknown compensation step '"
                                + step.compensationStepId() + "'.");
            }
        }
        // Reject precondition cycles. A step waits for its preconditions to COMPLETE, so a
        // cycle (A waits for B waits for … waits for A) would deadlock: none of those steps
        // could ever start. Steps may have several preconditions, so run a DFS (white/grey/
        // black) over the multi-edge graph — revisiting a grey node means a cycle.
        var preconditions = new java.util.HashMap<String, List<String>>();
        for (var step : steps) {
            if (step.id() != null) {
                preconditions.put(step.id(), step.preconditionIds());
            }
        }
        var acyclic = new java.util.HashSet<String>();
        for (var start : preconditions.keySet()) {
            checkNoPreconditionCycle(start, preconditions, new java.util.LinkedHashSet<>(), acyclic);
        }
    }

    /** The steps some other step names as its {@code compensationStepId}. */
    private java.util.Set<String> compensationTargets() {
        var targets = new java.util.HashSet<String>();
        for (var step : steps) {
            if (step.compensationStepId() != null && !step.compensationStepId().isBlank()) {
                targets.add(step.compensationStepId());
            }
        }
        return targets;
    }

    /**
     * Non-fatal style guidance toward the FORK/JOIN gateway model: a normal step should have a
     * single incoming and a single outgoing flow, using FORK to split and JOIN to merge. These are
     * WARNINGS, not errors — edges into a compensation step are excluded from the counts (a
     * compensation needs no incoming edge at all now, but definitions written before that anchored
     * it to some step with a permanently false guard, and that anchor was never real flow) — and
     * conditional splits (several guarded successors) stay allowed. Returns one message per node
     * that could be clearer.
     */
    public List<String> topologyWarnings() {
        if (steps == null || steps.isEmpty()) return List.of();
        var compensationTargets = compensationTargets();
        // Real outgoing edges per node, excluding the anchor edges into compensation steps.
        var realOut = new java.util.LinkedHashMap<String, Integer>();
        for (var step : steps) {
            for (var pre : step.preconditionIds()) {
                if (compensationTargets.contains(step.id())) continue; // anchor edge — not real flow
                realOut.merge(pre, 1, Integer::sum);
            }
        }
        var warnings = new java.util.ArrayList<String>();
        for (var step : steps) {
            if (step.id() == null) continue;
            int in = step.preconditionIds().size();
            if (in > 1 && step.type() != StepType.JOIN) {
                warnings.add("Step '" + step.id() + "' has " + in + " incoming flows but is not a JOIN"
                        + " — merge branches through a JOIN so its semantics (all vs any) are explicit.");
            }
            int out = realOut.getOrDefault(step.id(), 0);
            if (out > 1 && step.type() != StepType.FORK) {
                warnings.add("Step '" + step.id() + "' has " + out + " outgoing flows but is not a FORK"
                        + " — split through a FORK to keep the graph unambiguous.");
            }
        }
        return warnings;
    }

    /** DFS step for cycle detection: {@code path} is the grey set (current walk), {@code acyclic} the black set. */
    private void checkNoPreconditionCycle(String stepId, java.util.Map<String, List<String>> preconditions,
                                          java.util.LinkedHashSet<String> path, java.util.Set<String> acyclic) {
        if (acyclic.contains(stepId)) return;
        if (!path.add(stepId)) {
            throw new IllegalStateException(
                    "Steps form a precondition cycle (" + String.join(" → ", path)
                            + " → " + stepId + "), so none of them could ever run.");
        }
        for (var preconditionStepId : preconditions.getOrDefault(stepId, List.of())) {
            checkNoPreconditionCycle(preconditionStepId, preconditions, path, acyclic);
        }
        path.remove(stepId);
        acyclic.add(stepId);
    }

    @Override
    public int maxConcurrentExecutions() {
        return (limitConcurrentExecutions)?maxConcurrentExecutions:1;
    }

    @Override
    public List<Step> steps() {
        return steps != null?steps:List.of();
    }

    @Override
    public ListingData<io.mateu.uidl.data.Option> search(String fieldName, String searchText, Pageable pageable, HttpRequest httpRequest) {
        // A step cannot be its own precondition (nor its own compensation), so exclude the step
        // currently being edited from the options. Its state bubbles up with the lookup action
        // (see @Lookup(bubble = true) on preconditionStepId/compensationStepId).
        var currentStep = httpRequest == null ? null : httpRequest.getInitiatorState(Step.class);
        var currentStepId = currentStep == null ? null : currentStep.id();
        return ListingData.of(steps.stream()
                .filter(step -> currentStepId == null || !currentStepId.equals(step.id()))
                .map(step -> new io.mateu.uidl.data.Option(
                        step.id(),
                        step.name()
                ))
                .toList());
    }
}
