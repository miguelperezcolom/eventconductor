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
import io.mateu.workflow.application.usecases.lifecycle.ArchiveWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.DisableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.EnableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ReactivateWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.workingcopy.CreateWorkingCopyUseCase;
import io.mateu.workflow.application.usecases.workingcopy.PromoteWorkingCopyUseCase;
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
        // Read-only lifecycle status: shown in list/detail, never in the create or edit
        // forms. New definitions are created as DRAFT (see the canonical constructor below),
        // so it is never null despite not being editable.
        @HiddenInCreate
        @HiddenInEditor
        @Status(defaultStatus = StatusType.NONE, mappings = {
                @StatusMapping(from = "", to = StatusType.NONE),
                @StatusMapping(from = "DISABLED", to = StatusType.DANGER),
                @StatusMapping(from = "ARCHIVED", to = StatusType.NONE),
                @StatusMapping(from = "DRAFT", to = StatusType.WARNING),
                @StatusMapping(from = "ACTIVE", to = StatusType.SUCCESS),
        })
        WorkflowDefinitionStatus status,
        @Hidden
        String draftOfId,
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
        List<Step> steps
) implements Identifiable, SearchableText, LookupOptionsSupplier, VisibilitySupplier {

    /** New definitions (status not set in the editor) start their lifecycle as DRAFT. */
    public WorkflowDefinition {
        if (status == null) {
            status = WorkflowDefinitionStatus.DRAFT;
        }
    }

    /** Returns a copy of this definition with a different lifecycle status. */
    public WorkflowDefinition withStatus(WorkflowDefinitionStatus newStatus) {
        return new WorkflowDefinition(id, name, version, description, newStatus, draftOfId,
                limitConcurrentExecutions, maxConcurrentExecutions, enqueueOnLimit, cronExpression,
                defaultMaxStepExecutions, steps);
    }

    // ── Detail-view lifecycle buttons (conditional on state via VisibilitySupplier) ──

    /** Hides the built-in {@code edit} action and the lifecycle buttons per current status. */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "edit" -> status == WorkflowDefinitionStatus.ACTIVE;
            // Any DRAFT can be promoted: a working copy replaces its original, a standalone
            // draft is activated in place (see PromoteWorkingCopyUseCase).
            case "promoteToProduction" -> status != WorkflowDefinitionStatus.DRAFT;
            case "createWorkingCopy" -> status != WorkflowDefinitionStatus.ACTIVE;
            case "disable" -> status != WorkflowDefinitionStatus.ACTIVE;
            case "enable" -> status != WorkflowDefinitionStatus.DISABLED;
            case "reactivate" -> status != WorkflowDefinitionStatus.ARCHIVED;
            // An ACTIVE workflow must be disabled before it can be archived.
            case "archive" -> status == WorkflowDefinitionStatus.ACTIVE
                    || status == WorkflowDefinitionStatus.ARCHIVED;
            default -> false;
        };
    }

    @Toolbar
    public UICommand promoteToProduction(HttpRequest httpRequest) {
        var promotedId = MateuBeanProvider.getBean(PromoteWorkingCopyUseCase.class).handle(id);
        return navigateToDefinition(promotedId, httpRequest);
    }

    @Toolbar
    public UICommand createWorkingCopy(HttpRequest httpRequest) {
        var copyId = MateuBeanProvider.getBean(CreateWorkingCopyUseCase.class).handle(id);
        return navigateToDefinition(copyId, httpRequest);
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

    @Toolbar
    public UICommand reactivate(HttpRequest httpRequest) {
        MateuBeanProvider.getBean(ReactivateWorkflowDefinitionUseCase.class).handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    @Toolbar
    public UICommand archive(HttpRequest httpRequest) {
        MateuBeanProvider.getBean(ArchiveWorkflowDefinitionUseCase.class).handle(id);
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
        var stepIds = new java.util.HashSet<String>();
        for (var step : steps) {
            if (step.id() == null) continue;
            if (!stepIds.add(step.id())) {
                throw new IllegalStateException(
                        "Duplicate step id '" + step.id() + "'.");
            }
            if (step.id().equals(step.preconditionStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' cannot have itself as a precondition.");
            }
            if (step.id().equals(step.compensationStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' cannot have itself as a compensation step.");
            }
            if (StepType.TIMER.equals(step.type()) && step.duration() <= 0
                    && (step.untilVariable() == null || step.untilVariable().isBlank())) {
                throw new IllegalStateException(
                        "Timer step '" + step.id() + "' must define a duration or an untilVariable.");
            }
            if (StepType.MESSAGE.equals(step.type())
                    && (step.messageName() == null || step.messageName().isBlank())) {
                throw new IllegalStateException(
                        "Message step '" + step.id() + "' must define a messageName.");
            }
        }
        for (var step : steps) {
            if (step.id() == null) continue;
            if (step.preconditionStepId() != null && !step.preconditionStepId().isBlank()
                    && !stepIds.contains(step.preconditionStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' references unknown precondition step '"
                                + step.preconditionStepId() + "'.");
            }
            if (step.compensationStepId() != null && !step.compensationStepId().isBlank()
                    && !stepIds.contains(step.compensationStepId())) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' references unknown compensation step '"
                                + step.compensationStepId() + "'.");
            }
        }
        // Reject precondition cycles. A step waits for its preconditionStepId to COMPLETE, so a
        // cycle (A waits for B waits for … waits for A) would deadlock: none of those steps could
        // ever start. Each step has at most one precondition, so following the pointers and
        // revisiting a node on the current walk means a cycle.
        var precondition = new java.util.HashMap<String, String>();
        for (var step : steps) {
            if (step.id() != null && step.preconditionStepId() != null
                    && !step.preconditionStepId().isBlank()) {
                precondition.put(step.id(), step.preconditionStepId());
            }
        }
        var acyclic = new java.util.HashSet<String>();
        for (var start : precondition.keySet()) {
            if (acyclic.contains(start)) continue;
            var path = new java.util.LinkedHashSet<String>();
            var current = start;
            while (current != null) {
                if (acyclic.contains(current)) break;
                if (!path.add(current)) {
                    throw new IllegalStateException(
                            "Steps form a precondition cycle (" + String.join(" → ", path)
                                    + " → " + current + "), so none of them could ever run.");
                }
                current = precondition.get(current);
            }
            acyclic.addAll(path);
        }
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
