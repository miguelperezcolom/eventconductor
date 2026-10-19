package io.mateu.workflow.infra.in.ui.pages.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
    @Help("Templates over the event, by name: bookingId = ${data.bookingId}. A value that is JSON (an object"
            + " or a list) is kept as such: {\"id\": \"${data.id}\"}. With none listed, see the switch below.")
    List<Variable> variables;

    @Label("Map data's top-level members")
    @Help("Only when no variables are listed: on, every top-level member of data becomes a variable; off, the"
            + " started process gets no variables at all.")
    boolean dataMembers = true;

    @Label("Start policy")
    @io.mateu.uidl.annotations.Stereotype(io.mateu.uidl.data.FieldStereotype.textarea)
    @Help("Optional per-key flow control of the starts, as JSON: {\"key\": \"${data.customerId}\", \"debounce\": \"PT30S\"},"
            + " {\"throttle\": {\"limit\": 10, \"period\": \"PT1M\", \"onLimit\": \"queue\"}}, {\"singleton\": \"skip\"}."
            + " Empty: the workflow's own startPolicy, if any.")
    String startPolicy;

    /** The form for editing {@code view}. */
    static EventTriggerForm of(TriggerView view) {
        var form = new EventTriggerForm();
        form.id = view.triggerId();
        form.workflowId = view.workflowId();
        form.destination = view.destination();
        form.type = view.type();
        form.filter = view.filter();
        form.businessKey = view.businessKey();
        // null is "data's top-level members", {} is "none": two different triggers.
        form.dataMembers = view.variables() == null;
        form.startPolicy = view.startPolicy() == null ? null : text(view.startPolicy());
        form.variables = view.variables() == null ? new ArrayList<>() : new ArrayList<>(view.variables().entrySet().stream()
                .map(e -> new Variable(e.getKey(), text(e.getValue())))
                .toList());
        return form;
    }

    /**
     * What the form says, for the service: the listed variables; with none, null ("data's top-level
     * members") when {@link #dataMembers} is on, else an empty map ("none").
     */
    TriggerDraft draft() {
        LinkedHashMap<String, Object> map;
        if (variables != null && !variables.isEmpty()) {
            map = new LinkedHashMap<>();
            for (var v : variables) {
                map.put(v.name(), template(v.value()));
            }
        } else {
            map = dataMembers ? null : new LinkedHashMap<>();
        }
        return new TriggerDraft(id, workflowId, destination, type, filter, businessKey, map, policy(startPolicy));
    }

    /** The start policy the form's JSON describes; null when empty. */
    static io.mateu.workflow.domain.aggregates.StartPolicy policy(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, io.mateu.workflow.domain.aggregates.StartPolicy.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Start policy: not a JSON object of key, debounce, throttle and singleton ("
                    + e.getOriginalMessage() + ")");
        }
    }

    /** A template as the form shows it: text as it is, anything else (a nested map, a list, a number) as JSON. */
    static String text(Object template) {
        if (template == null || template instanceof String) {
            return (String) template;
        }
        try {
            return JSON.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            return String.valueOf(template);
        }
    }

    /** The template a form value stands for: an object or a list written as JSON is that structure again. */
    static Object template(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (JsonProcessingException notJson) {
                return value;
            }
        }
        return value;
    }
}
