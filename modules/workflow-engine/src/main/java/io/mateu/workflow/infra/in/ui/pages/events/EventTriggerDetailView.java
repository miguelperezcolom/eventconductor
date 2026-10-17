package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;
import io.mateu.workflow.infra.in.ui.pages.schedules.ScheduleFormats;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * An event trigger's own page: which event starts which workflow, what the process gets, where the
 * trigger is declared and whether it fires, with what can be done to it.
 *
 * <p>Any trigger can be paused and resumed. Edit and Delete are only offered for the ones created here
 * (source UI/API): a trigger declared in a definition or an {@code events.yaml} is changed in that file
 * — the page says where. "Promote to git" gives the {@code events.yaml} entry of the trigger, id and
 * workflow included: commit it next to the definitions and, on the next import, the file owns the
 * trigger.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class EventTriggerDetailView implements VisibilitySupplier {

    final EventTriggerService eventTriggerService;
    final ObjectProvider<CallerResolver> callerResolver;

    @Hidden
    String id;

    @Hidden
    String workflowId;

    @Hidden
    String triggerId;

    @Hidden
    boolean editable;

    @Hidden
    boolean paused;

    /** Promoted to a header badge. */
    Status status;

    @Label("Workflow")
    String workflow;

    @Label("Destination → topic")
    String destination;

    @Label("Type")
    String type;

    @Label("Filter")
    String filter;

    @Label("Business key")
    String businessKey;

    @Label("Variables")
    String variables;

    @Label("Source")
    String source;

    @Label("Ownership")
    String ownership;

    @Label("Paused")
    String pausedInfo;

    /** The events.yaml entry that promotes this trigger to git, across the whole width (see ScheduleDetailView). */
    @Label("")
    @Colspan(2)
    Element details;

    public EventTriggerDetailView load(String rowId) {
        return fill(eventTriggerService.get(EventTriggerRow.workflowOf(rowId), EventTriggerRow.triggerOf(rowId)));
    }

    EventTriggerDetailView fill(TriggerView view) {
        this.id = EventTriggerRow.rowId(view);
        this.workflowId = view.workflowId();
        this.triggerId = view.triggerId();
        this.editable = view.editable();
        this.paused = view.paused();
        this.status = EventFormats.status(view.state());
        this.workflow = view.workflowName() == null || view.workflowName().equals(view.workflowId())
                ? view.workflowId() : view.workflowName() + " (" + view.workflowId() + ")";
        this.destination = EventFormats.destination(view);
        this.type = view.type();
        this.filter = EventFormats.orDash(view.filter());
        this.businessKey = EventFormats.businessKey(view);
        this.variables = EventFormats.variables(view);
        this.source = view.declaredIn();
        this.ownership = view.editable()
                ? "Created here — editable. Promote it to git to have a file own it."
                : view.duplicateOf() != null
                ? "Never fires: its id is already declared in " + view.duplicateOf() + "."
                : "Declared in a file — change it there. Here it can be paused or resumed.";
        this.pausedInfo = !view.paused() ? "no"
                : "since " + view.pausedAt() + (view.pausedBy() == null ? "" : " by " + view.pausedBy());
        var promotion = view.editable()
                ? "Paste this entry into the events.yaml next to the workflow definitions and commit it. On the next"
                        + " import the file owns trigger " + triggerId + " of " + workflowId + ": it replaces this one"
                        + " (its pause carries over), and removing it from the file deletes it."
                : "Already owned by a file: " + view.declaredIn() + ".";
        var yaml = view.duplicateOf() != null ? "" : eventTriggerService.exportYaml(workflowId, triggerId);
        this.details = Element.builder()
                .attributes(java.util.Map.of())
                .on(java.util.Map.of())
                .name("div")
                .html(true)
                .content("<h4>Promote to git</h4>"
                        + "<p>" + ScheduleFormats.escape(promotion) + "</p>"
                        + (yaml.isEmpty() ? "" : "<pre style=\"user-select: all; padding: 0.75em; border-radius: 4px;"
                        + " overflow-x: auto; background: var(--lumo-contrast-5pct, #f4f5f7);\">"
                        + ScheduleFormats.escape(yaml) + "</pre>"))
                .style("width: 100%;")
                .build();
        return this;
    }

    @Toolbar
    @Label("Pause")
    public UICommand pause(HttpRequest httpRequest) {
        eventTriggerService.pause(workflowId, triggerId, caller());
        return navigateToTrigger();
    }

    @Toolbar
    @Label("Resume")
    public UICommand resume(HttpRequest httpRequest) {
        eventTriggerService.resume(workflowId, triggerId);
        return navigateToTrigger();
    }

    @Toolbar
    @Label("Delete")
    @Action(confirmationRequired = true, confirmationTitle = "Delete trigger",
            confirmationMessage = "Delete this trigger? Events that match it no longer start the workflow.",
            confirmationText = "Delete", confirmationDenialText = "Cancel")
    public UICommand deleteTrigger(HttpRequest httpRequest) {
        eventTriggerService.delete(workflowId, triggerId);
        return navigateToList();
    }

    /**
     * Edit and Delete only for what was created here; Pause or Resume, whichever applies. The Crud's own
     * {@code delete} works on a listing selection, not on the trigger shown, so it is never offered:
     * {@link #deleteTrigger} is this page's.
     */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "new", "delete" -> true;
            case "edit", "deleteTrigger" -> !editable;
            case "pause" -> paused;
            case "resume" -> !paused;
            default -> false;
        };
    }

    private String caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        var context = resolver == null ? null : resolver.current();
        return context == null ? null : context.subject();
    }

    /** Reloads this trigger's page, relative to its own URL ({@code <base>/workflow/eventTriggers/<id>}). */
    private UICommand navigateToTrigger() {
        return UICommand.navigateTo(ScheduleFormats.pathSegment(id));
    }

    /** Back to the Event triggers listing, relative to this page's URL as above. */
    private static UICommand navigateToList() {
        return UICommand.navigateTo("../eventTriggers");
    }

    /** The view title. */
    @Override
    public String toString() {
        return triggerId != null ? "Trigger " + workflowId + " / " + triggerId : "Trigger";
    }
}
