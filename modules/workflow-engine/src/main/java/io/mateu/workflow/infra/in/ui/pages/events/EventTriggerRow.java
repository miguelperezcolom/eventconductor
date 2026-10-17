package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;

/**
 * One row of the Event triggers page: an event trigger of some workflow.
 *
 * @param id     {@code <workflow>~<trigger>} — the pause key with {@code ~} for {@code /}, so it travels
 *               in a URL path segment (see {@link #rowId}); neither id may contain {@code ~}
 * @param source "Definition", "events.yaml (git)", "events.yaml (directory)", "events.yaml (classpath)", "UI/API"
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
        return new EventTriggerRow(rowId(view), view.workflowId(), view.triggerId(), EventFormats.destination(view),
                view.type(), EventFormats.orDash(view.filter()), EventFormats.businessKey(view),
                EventFormats.variables(view), view.sourceLabel(), EventFormats.orDash(view.pausedBy()),
                EventFormats.status(view.state()));
    }

    /** The row id of a trigger: {@code <workflow>~<trigger>}. */
    public static String rowId(TriggerView view) {
        return rowId(view.workflowId(), view.triggerId());
    }

    public static String rowId(String workflowId, String triggerId) {
        return workflowId + "~" + triggerId;
    }

    /** The workflow of a row id: everything before the last {@code ~} (a trigger id never holds one). */
    public static String workflowOf(String rowId) {
        return rowId.substring(0, rowId.lastIndexOf('~'));
    }

    /** The trigger of a row id: everything after the last {@code ~}. */
    public static String triggerOf(String rowId) {
        return rowId.substring(rowId.lastIndexOf('~') + 1);
    }
}
