package io.mateu.workflow.infra.in.ui.pages.schedules;

import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.NotificationVariant;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * A schedule's own page: what it starts, when, where it is declared and whether it fires, with what
 * can be done to it.
 *
 * <p>Any schedule can be paused, resumed and started now. Edit and Delete are only offered for the ones
 * created here (source UI/API): a schedule declared in a definition or a {@code cron.yaml} is changed
 * in that file — the page says where. "Promote to git" gives the {@code cron.yaml} entry of the
 * schedule, id included: commit it next to the definitions and, on the next import, the file owns the
 * schedule.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ScheduleDetailView implements VisibilitySupplier {

    final ScheduleService scheduleService;
    final ObjectProvider<CallerResolver> callerResolver;

    @Hidden
    String id;

    @Hidden
    boolean editable;

    @Hidden
    boolean paused;

    /** Promoted to a header badge. */
    Status status;

    @Label("Workflow")
    String workflow;

    @Label("Cron")
    String cron;

    @Label("When")
    String description;

    @Label("Zone")
    String zone;

    @Label("Variables")
    String variables;

    @Label("Source")
    String source;

    @Label("Ownership")
    String ownership;

    @Label("Paused")
    String pausedInfo;

    @Label("Also declared in")
    String alsoDeclaredIn;

    /**
     * The next starts, and the cron.yaml entry that promotes this schedule to git. One Element across
     * the whole width: a read-only view shows a String as the value of a field, markup included, and
     * an Element sits in the form's grid like any field — so the markup is one, spanning both columns.
     */
    @Label("")
    @Colspan(2)
    Element details;

    public ScheduleDetailView load(String scheduleId) {
        return fill(scheduleService.get(scheduleId));
    }

    ScheduleDetailView fill(ScheduleView view) {
        this.id = view.id();
        this.editable = view.editable();
        this.paused = view.paused();
        this.status = ScheduleFormats.status(view.state());
        this.workflow = view.workflowName() == null || view.workflowName().equals(view.workflowId())
                ? view.workflowId() : view.workflowName() + " (" + view.workflowId() + ")";
        this.cron = view.cron();
        this.description = view.description() == null ? "—" : view.description();
        this.zone = ScheduleFormats.zone(view);
        this.variables = ScheduleFormats.variables(view);
        this.source = view.declaredIn();
        this.ownership = view.editable()
                ? "Created here — editable. Promote it to git to have a file own it."
                : "Declared in a file — change it there. Here it can be paused, resumed or started now.";
        this.pausedInfo = !view.paused() ? "no"
                : "since " + view.pausedAt() + (view.pausedBy() == null ? "" : " by " + view.pausedBy());
        this.alsoDeclaredIn = view.alsoDeclaredIn().isEmpty() ? "—"
                : String.join("; ", view.alsoDeclaredIn()) + " (ignored: one schedule per id)";
        var promotion = view.editable()
                ? "Paste this entry into the cron.yaml next to the workflow definitions and commit it. On the next"
                        + " import the file owns schedule " + id + ": it replaces this one (its pause carries over),"
                        + " and removing it from the file deletes it."
                : "Already owned by a file: " + view.declaredIn() + ".";
        this.details = Element.builder()
                .attributes(java.util.Map.of())
                .on(java.util.Map.of())
                .name("div")
                .html(true)
                .content("<h4>Next starts</h4>"
                        + (view.nextFires().isEmpty() ? "<p>—</p>" : view.nextFires().stream()
                                .map(f -> "<li>" + ScheduleFormats.escape(ScheduleFormats.fire(f)) + "</li>")
                                .collect(Collectors.joining("", "<ul>", "</ul>")))
                        + "<h4>Promote to git</h4>"
                        + "<p>" + ScheduleFormats.escape(promotion) + "</p>"
                        + "<pre style=\"user-select: all; padding: 0.75em; border-radius: 4px; overflow-x: auto;"
                        + " background: var(--lumo-contrast-5pct, #f4f5f7);\">"
                        + ScheduleFormats.escape(scheduleService.exportYaml(id)) + "</pre>")
                .style("width: 100%;")
                .build();
        return this;
    }

    @Toolbar
    @Label("Pause")
    public UICommand pause(HttpRequest httpRequest) {
        scheduleService.pause(id, caller());
        return navigateToSchedule(id, httpRequest);
    }

    @Toolbar
    @Label("Resume")
    public UICommand resume(HttpRequest httpRequest) {
        scheduleService.resume(id);
        return navigateToSchedule(id, httpRequest);
    }

    @Toolbar
    @Label("Run now")
    @Action(confirmationRequired = true, confirmationTitle = "Run now",
            confirmationMessage = "Start the workflow once, now, with this schedule's variables?",
            confirmationText = "Run now", confirmationDenialText = "Cancel")
    public Message runNow(HttpRequest httpRequest) {
        // Nothing on this page changes, so it stays as it is and only says what was started.
        var started = scheduleService.runNow(id, caller(), callerContext());
        return Message.builder()
                .variant(NotificationVariant.success)
                .text("Started " + started.workflowId() + " — business key " + started.businessKey())
                .build();
    }

    @Toolbar
    @Label("Delete")
    @Action(confirmationRequired = true, confirmationTitle = "Delete schedule",
            confirmationMessage = "Delete this schedule? It stops starting its workflow.",
            confirmationText = "Delete", confirmationDenialText = "Cancel")
    public UICommand deleteSchedule(HttpRequest httpRequest) {
        scheduleService.delete(id);
        return navigateToList(httpRequest);
    }

    /**
     * Edit and Delete only for what was created here; Pause or Resume, whichever applies. The Crud's own
     * {@code delete} works on a listing selection, not on the schedule shown, so it is never offered:
     * {@link #deleteSchedule} is this page's.
     */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "new", "delete" -> true;
            case "edit", "deleteSchedule" -> !editable;
            case "pause" -> paused;
            case "resume" -> !paused;
            default -> false;
        };
    }

    /** The caller as resolved at this request, for the flow-authorization check. */
    private io.mateu.workflow.security.AuthorizationContext callerContext() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    private String caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        var context = resolver == null ? null : resolver.current();
        return context == null ? null : context.subject();
    }

    /**
     * Reloads this schedule's page, so what it shows is what the engine now holds. Relative to the
     * page's own URL ({@code <base>/workflow/schedules/<id>}), so it lands right whichever console —
     * and whatever base URL — the page was opened in.
     */
    private static UICommand navigateToSchedule(String scheduleId, HttpRequest httpRequest) {
        return UICommand.navigateTo(ScheduleFormats.pathSegment(scheduleId));
    }

    /** Back to the Schedules listing, relative to this page's URL as above. */
    private static UICommand navigateToList(HttpRequest httpRequest) {
        return UICommand.navigateTo("../schedules");
    }

    /** The view title. */
    @Override
    public String toString() {
        return id != null ? "Schedule " + id : "Schedule";
    }
}
