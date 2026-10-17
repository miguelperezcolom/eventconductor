package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain events in, as MCP tools: consult and operate the event triggers, see what the received events
 * did, and inject one. Collected with the other {@link McpTools} beans.
 *
 * <p>Every tool returns a concrete type: Spring AI silently drops a tool returning {@code Object}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventTriggerMcpTools implements McpTools, McpSystemContext {

    private static final String CALLER = "mcp";

    private final EventTriggerService eventTriggerService;
    private final InboundEventService inboundEventService;

    @Override
    public String getSystemContext() {
        return """
                Eventos de dominio entrantes (event triggers):
                - Una definición puede declarar triggers: cuando llega un evento de dominio (CloudEvents) de cierto type a un destination (nombre lógico mapeado a un topic con workflow.events.destinations.<name>.topic), el motor arranca un proceso, con filter (JEXL sobre el evento), businessKey y variables (plantillas sobre el evento: data.x, id, subject, key…).
                - Un paso WAIT_FOR_MESSAGE con onEvent espera un evento de dominio: el evento se convierte en su mensaje (messageName) con la clave de correlación que renderiza (correlationKey, por defecto la key del registro o el subject).
                - Cada evento (source + id) se usa una sola vez por trigger y por mensaje/clave, aunque llegue varias veces.
                - Un trigger se declara en la definición, en un events.yaml junto a las definiciones (la misma entrada más workflow: <id>), o se crea desde aquí (origen UI/API) con createEventTrigger; updateEventTrigger / deleteEventTrigger solo valen para los creados aquí (los de fichero se cambian en su fichero); exportEventTriggerYaml devuelve la entrada de events.yaml para promoverlo a git (al importarse, el fichero se queda con el trigger y la copia creada aquí se borra).
                - listEventTriggers lista los triggers con su origen (DEFINITION, EVENTS_FILE, API) y estado; pauseEventTrigger / resumeEventTrigger los operan (un trigger pausado no arranca nada y no recupera lo perdido); listReceivedEvents enseña qué hizo cada evento recibido; publishInboundEvent inyecta un evento (para pruebas o sistemas sin Kafka).
                """;
    }

    @Tool(description = "List the event triggers of every workflow definition (or of one): workflow, trigger id,"
            + " destination and its topic (null when not configured), CloudEvents type pattern, filter, business key"
            + " template, variable templates, the workflow's status and whether the trigger is paused. state is ACTIVE"
            + " (starts processes), PAUSED or INACTIVE (its workflow is disabled or archived).")
    public List<TriggerView> listEventTriggers(
            @ToolParam(required = false, description = "Only the triggers of this workflow definition id; empty for all")
            String workflowId) {
        return eventTriggerService.list(workflowId, null);
    }

    @Tool(description = "Pause an event trigger: matching events start nothing until it is resumed, and the ones that"
            + " arrived meanwhile are not replayed. Kept by <workflowId>/<triggerId>, so it survives re-imports.")
    public TriggerView pauseEventTrigger(String workflowId, String triggerId) {
        log.info("Pausing trigger {}/{}", workflowId, triggerId);
        return eventTriggerService.pause(workflowId, triggerId, CALLER);
    }

    @Tool(description = "Resume a paused event trigger: events that arrive from now on start processes again.")
    public TriggerView resumeEventTrigger(String workflowId, String triggerId) {
        log.info("Resuming trigger {}/{}", workflowId, triggerId);
        return eventTriggerService.resume(workflowId, triggerId);
    }

    @Tool(description = "Create an event trigger (source UI/API): a domain event of the given CloudEvents type arriving on"
            + " the destination starts the workflow. id: a free name such as on-booking-created (letters, digits, '.', '_',"
            + " '-'), unique within the workflow; workflowId: an existing workflow; destination: a logical name under"
            + " workflow.events.destinations; type: the CloudEvents type, '*' wildcards allowed ('*' alone matches every"
            + " event, including plain records); filter: optional JEXL over the event (data.op == 'u'); businessKey:"
            + " optional template (customer-${data.after.id}); variables: templates by name — omitted/null maps data's top-level members, {} maps none."
            + " Returns the trigger with its state.")
    public TriggerView createEventTrigger(String id, String workflowId, String destination, String type,
                                          @ToolParam(required = false, description = "JEXL filter over the event; empty for none") String filter,
                                          @ToolParam(required = false, description = "Business key template; empty for the default") String businessKey,
                                          @ToolParam(required = false, description = "Variable templates by name (name -> template over the event, e.g. {\"customerId\": \"${data.after.id}\"}). Omitted or null: every top-level member of data becomes a variable. An empty object {}: the process gets no variables.") Map<String, Object> variables) {
        log.info("Creating event trigger {} for {}", id, workflowId);
        return eventTriggerService.create(new EventTriggerService.TriggerDraft(id, workflowId, destination, type, filter,
                businessKey, variables));
    }

    @Tool(description = "Edit a UI/API event trigger: destination, type, filter, business key and variables are replaced;"
            + " the workflow and the id never change. Fails for a trigger declared in a definition or an events.yaml: those"
            + " are edited in their file.")
    public TriggerView updateEventTrigger(String workflowId, String triggerId, String destination, String type,
                                          @ToolParam(required = false, description = "JEXL filter over the event; empty for none") String filter,
                                          @ToolParam(required = false, description = "Business key template; empty for the default") String businessKey,
                                          @ToolParam(required = false, description = "Variable templates by name (name -> template over the event, e.g. {\"customerId\": \"${data.after.id}\"}). Omitted or null: every top-level member of data becomes a variable. An empty object {}: the process gets no variables.") Map<String, Object> variables) {
        log.info("Updating event trigger {}/{}", workflowId, triggerId);
        return eventTriggerService.update(workflowId, triggerId, new EventTriggerService.TriggerDraft(triggerId, workflowId,
                destination, type, filter, businessKey, variables));
    }

    @Tool(description = "Delete a UI/API event trigger (and its pause). Fails for a trigger declared in a definition or an"
            + " events.yaml: remove it from its file instead.")
    public String deleteEventTrigger(String workflowId, String triggerId) {
        log.info("Deleting event trigger {}/{}", workflowId, triggerId);
        eventTriggerService.delete(workflowId, triggerId);
        return "Trigger " + workflowId + "/" + triggerId + " deleted";
    }

    @Tool(description = "Promote an event trigger to git: returns its events.yaml entry (id and workflow included), ready"
            + " to paste into the events.yaml next to the workflow definitions. Once that file is imported, the file owns"
            + " the trigger: it replaces the UI/API one with the same workflow and id (keeping its pause), and removing it"
            + " from the file deletes it.")
    public String exportEventTriggerYaml(String workflowId, String triggerId) {
        return eventTriggerService.exportYaml(workflowId, triggerId);
    }

    @Tool(description = "The latest received domain events and what each did, newest first: destination, type, id,"
            + " source, receiver (trigger:<workflow>/<trigger> or wait:<message>/<key>), outcome (STARTED a process with"
            + " businessKey, DELIVERED as a message, or ERROR with detail). Optionally one workflow's.")
    public List<EventReceipt> listReceivedEvents(
            @ToolParam(required = false, description = "Only this workflow definition id; empty for all") String workflowId,
            @ToolParam(required = false, description = "How many, default 50") Integer limit) {
        return eventTriggerService.received(limit == null ? 50 : limit, workflowId);
    }

    @Tool(description = "Inject a domain event (a CloudEvent) on a destination, as if it had arrived on its topic: it"
            + " starts the workflows whose triggers match and completes the WAIT_FOR_MESSAGE steps whose onEvent matches"
            + " and whose correlation key it carries. Returns what it did. id and source identify the event: the same pair"
            + " sent twice is used once. data is the payload as JSON text, e.g. {\"bookingId\":\"B-1\"}.")
    public InboundEventService.Result publishInboundEvent(
            String destination, String type,
            @ToolParam(description = "The payload as JSON text") String data,
            @ToolParam(required = false, description = "CloudEvents id; empty for a new random one") String id,
            @ToolParam(required = false, description = "CloudEvents source; empty for 'mcp'") String source,
            @ToolParam(required = false, description = "CloudEvents subject") String subject,
            @ToolParam(required = false, description = "The record key (default correlation key of onEvent waits)") String key) {
        var event = new InboundEvent(destination, id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                source == null || source.isBlank() ? CALLER : source, type, subject, null, key, "application/json", data,
                Map.of());
        log.info("MCP domain event {} ({}) on '{}'", event.id(), type, destination);
        return inboundEventService.receive(event);
    }
}
