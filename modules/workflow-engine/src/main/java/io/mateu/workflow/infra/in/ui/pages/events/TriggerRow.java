package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;

/**
 * One row of a definition's "Triggers" tab: a domain event that starts the workflow — where it arrives,
 * which type, the filter, the business key and variables it maps, where it is declared (the definition
 * or an {@code events.yaml}), and whether it starts processes now (paused and resumed on Workflow → Events).
 */
public record TriggerRow(
        @Hidden String id,
        @Label("Trigger") String trigger,
        @Label("Destination → topic") String destination,
        @Label("Type") String type,
        @Label("Filter") String filter,
        @Label("Business key") String businessKey,
        @Label("Variables") String variables,
        @Label("Source") String source,
        @Label("Status") Status status
) implements Identifiable {

    public static TriggerRow of(TriggerView view) {
        return new TriggerRow(view.key(), view.triggerId(), EventFormats.destination(view), view.type(),
                EventFormats.orDash(view.filter()), EventFormats.businessKey(view), EventFormats.variables(view),
                view.sourceLabel(), EventFormats.status(view.state()));
    }
}
