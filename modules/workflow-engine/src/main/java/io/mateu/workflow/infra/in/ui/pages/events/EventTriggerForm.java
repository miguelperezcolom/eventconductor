package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.workflow.application.services.EventTriggerService.TriggerDraft;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The create and edit form of an event trigger created here: its id and workflow (fixed once created),
 * which event starts the workflow, and what the started process gets.
 *
 * <p>Fields only, like {@code ScheduleForm}: the {@code EventTriggers} Crud draws Create/Save and does
 * the work in {@link EventTriggers#create} and {@link EventTriggers#save}, where every rule is checked
 * and the page lands on the trigger's detail.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@FormLayout(columns = 1)
public class EventTriggerForm {

    @NotNull
    @EditableOnlyWhenCreating
    @Label("Id")
    @Help("The trigger's name within its workflow: letters, digits, '.', '_' and '-'. It never changes,"
            + " and it is how the events.yaml entry you may later commit to git takes this trigger over.")
    String id;

    @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
    @NotNull
    @EditableOnlyWhenCreating
    @Label("Workflow")
    String workflowId;

    @NotNull
    @Label("Destination")
    @Help("A logical destination, mapped to a topic by workflow.events.destinations.<name>.topic — the same"
            + " names PUBLISH_EVENT publishes to, never a topic itself.")
    String destination;

    @NotNull
    @Label("Type")
    @Help("The CloudEvents type to match, e.g. com.acme.booking.created; '*' is a wildcard"
            + " (com.acme.booking.*), and '*' alone matches every event, plain records included.")
    String type;

    @Label("Filter")
    @Help("Optional JEXL over the event, without ${…}: data.channel == 'web'. Only true lets the event through.")
    String filter;

    @Label("Business key")
    @Help("Optional template over the event, e.g. booking-${data.bookingId}. Empty: a key derived from the"
            + " event's source and id, so a redelivery never starts a second process.")
    String businessKey;

    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    @Label("Variables")
    @Help("Templates over the event, by name: bookingId = ${data.bookingId}. None: every top-level member"
            + " of data becomes a variable.")
    List<Variable> variables;

    /** The form for editing {@code view}. */
    static EventTriggerForm of(TriggerView view) {
        var form = new EventTriggerForm();
        form.id = view.triggerId();
        form.workflowId = view.workflowId();
        form.destination = view.destination();
        form.type = view.type();
        form.filter = view.filter();
        form.businessKey = view.businessKey();
        form.variables = view.variables() == null ? new ArrayList<>() : new ArrayList<>(view.variables().entrySet().stream()
                .map(e -> new Variable(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue())))
                .toList());
        return form;
    }

    /** What the form says, for the service: no variables means "data's top-level members". */
    TriggerDraft draft() {
        LinkedHashMap<String, Object> map = null;
        if (variables != null && !variables.isEmpty()) {
            map = new LinkedHashMap<>();
            for (var v : variables) {
                map.put(v.name(), v.value());
            }
        }
        return new TriggerDraft(id, workflowId, destination, type, filter, businessKey, map);
    }
}
