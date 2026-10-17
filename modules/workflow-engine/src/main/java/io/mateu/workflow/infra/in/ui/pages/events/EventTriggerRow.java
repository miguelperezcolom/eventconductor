package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;

/**
 * One row of the Events page: an event trigger of some workflow.
 *
 * @param id     {@code <workflow>/<trigger>}, the key its pause is kept under
 * @param source "Definition", "events.yaml (git)", "events.yaml (directory)", "events.yaml (classpath)"
 */
public record EventTriggerRow(
        @Label("Id") String id,
        @Label("Workflow") String workflow,
        @Label("Trigger") String trigger,
        @Label("Destination → topic") String destination,
        @Label("Type") String type,
        @Label("Filter") String filter,
        @Label("Business key") String businessKey,
        @Label("Variables") String variables,
        @Label("Source") String source,
        @Label("Paused by") String pausedBy,
        @Label("Status") Status status
) implements Identifiable {

    public static EventTriggerRow of(TriggerView view) {
        return new EventTriggerRow(view.key(), view.workflowId(), view.triggerId(), EventFormats.destination(view),
                view.type(), EventFormats.orDash(view.filter()), EventFormats.businessKey(view),
                EventFormats.variables(view), view.sourceLabel(), EventFormats.orDash(view.pausedBy()),
                EventFormats.status(view.state()));
    }
}
