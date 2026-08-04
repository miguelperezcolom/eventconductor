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
        // Runtime disabled flag: while true the definition accepts no new instances (cron
        // included). Never in the .ec — toggled through Disable/EnableWorkflowDefinitionUseCase.
        @Hidden
        boolean disabled,
        // Runtime archived flag: set by the git-import prune when a definition disappears from
        // its repository, to hide it without deleting. Never in the .ec.
        @Hidden
        boolean archived
) implements Identifiable, SearchableText, LookupOptionsSupplier, VisibilitySupplier {

    /** Creation without the runtime flags: definitions start unpaused, enabled and not archived. */
    public WorkflowDefinition(String id, String name, int version, String description,
                              boolean limitConcurrentExecutions, int maxConcurrentExecutions,
                              boolean enqueueOnLimit, String cronExpression,
                              int defaultMaxStepExecutions, List<Step> steps) {
        this(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, false, false, false);
    }

    /** Returns a copy with a different runtime pause flag. */
    public WorkflowDefinition withPaused(boolean newPaused) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, newPaused, disabled, archived);
    }

    /** Returns a copy with a different runtime disabled flag. */
    public WorkflowDefinition withDisabled(boolean newDisabled) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, newDisabled, archived);
    }

    /** Returns a copy with a different runtime archived flag. */
    public WorkflowDefinition withArchived(boolean newArchived) {
        return new WorkflowDefinition(id, name, version, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, disabled, newArchived);
    }

    /** Returns a copy carrying the given (engine-assigned) version number. */
    public WorkflowDefinition withVersion(int newVersion) {
        return new WorkflowDefinition(id, name, newVersion, description, limitConcurrentExecutions,
                maxConcurrentExecutions, enqueueOnLimit, cronExpression, defaultMaxStepExecutions,
                steps, paused, disabled, archived);
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
            case "disable" -> disabled;
            case "enable" -> !disabled;
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
            if (step.preconditions().contains(step.id())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' cannot have itself as a precondition.");
            }
            if (step.id().equals(step.compensationStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' cannot have itself as a compensation step.");
            }
            if (StepType.START.equals(step.type()) && !step.preconditions().isEmpty()) {
                throw new IllegalStateException(
                        "START step '" + step.id() + "' cannot have preconditions.");
            }
            if (!StepType.START.equals(step.type()) && !StepType.WAIT_FOR_MESSAGE.equals(step.type())
                    && step.preconditions().isEmpty()) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' has no preconditions but is not a START or"
                                + " WAIT_FOR_MESSAGE — every flow must enter through one.");
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
            for (var preconditionStepId : step.preconditions()) {
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
                preconditions.put(step.id(), step.preconditions());
            }
        }
        var acyclic = new java.util.HashSet<String>();
        for (var start : preconditions.keySet()) {
            checkNoPreconditionCycle(start, preconditions, new java.util.LinkedHashSet<>(), acyclic);
        }
    }

    /**
     * Non-fatal style guidance toward the FORK/JOIN gateway model: a normal step should have a
     * single incoming and a single outgoing flow, using FORK to split and JOIN to merge. These are
     * WARNINGS, not errors — compensation anchors (the false-guarded edge into a step that is some
     * other step's {@code compensationStepId}) are excluded from the counts, and conditional splits
     * (several guarded successors) stay allowed. Returns one message per node that could be clearer.
     */
    public List<String> topologyWarnings() {
        if (steps == null || steps.isEmpty()) return List.of();
        var compensationTargets = new java.util.HashSet<String>();
        for (var step : steps) {
            if (step.compensationStepId() != null && !step.compensationStepId().isBlank()) {
                compensationTargets.add(step.compensationStepId());
            }
        }
        // Real outgoing edges per node, excluding the anchor edges into compensation steps.
        var realOut = new java.util.LinkedHashMap<String, Integer>();
        for (var step : steps) {
            for (var pre : step.preconditions()) {
                if (compensationTargets.contains(step.id())) continue; // anchor edge — not real flow
                realOut.merge(pre, 1, Integer::sum);
            }
        }
        var warnings = new java.util.ArrayList<String>();
        for (var step : steps) {
            if (step.id() == null) continue;
            int in = step.preconditions().size();
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
