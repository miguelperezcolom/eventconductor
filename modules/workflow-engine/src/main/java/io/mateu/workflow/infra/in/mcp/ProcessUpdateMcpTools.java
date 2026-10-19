package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Process updates as MCP tools: apply a declared update to a running process and get its result, update
 * a process by business key starting it when there is none, list a process's updates. The same service the
 * REST API uses ({@link ProcessUpdateService}), so the same validation, authorization and idempotency. A
 * refusal comes back as a sentence saying why, not as an error.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessUpdateMcpTools implements McpTools, McpSystemContext {

    private final ProcessUpdateService updates;

    @Autowired(required = false)
    ObjectProvider<CallerResolver> callerResolver;

    @Override
    public String getSystemContext() {
        return """
                Updates de procesos en marcha (declarados en la definición, sección updates: name, accepts = JSON Schema del payload, validate = guardas, set = variables que escribe, reply = resultado):
                - updateProcess aplica un update a un proceso RUNNING o PENDING y devuelve su resultado. Se valida el payload contra el esquema, luego las guardas; si alguna falla no cambia nada. idempotencyKey (opcional) hace que un reintento devuelva el mismo resultado.
                - updateWithStart hace lo mismo sobre el proceso con un businessKey, arrancándolo si no existe (el update es lo primero que ve).
                - listProcessUpdates lista los updates de un proceso (nombre, quién, estado, resultado; el payload con los campos sensibles enmascarados).
                - Distinto de sendMessage (asíncrono, sin respuesta) y de setProcessVariables (operación de administrador con motivo).
                """;
    }

    @Tool(description = "Apply a named update (declared in the process's definition under `updates`) to a running workflow"
            + " process and return its result. The payload is JSON validated against the update's schema; the update's"
            + " guards may refuse it (nothing changes then). Name the process by processId or by businessKey.")
    public String updateProcess(
            @ToolParam(required = false, description = "The process id (or give businessKey)") String processId,
            @ToolParam(required = false, description = "The process's business key (instead of processId)") String businessKey,
            @ToolParam(description = "The update's name") String updateName,
            @ToolParam(required = false, description = "The payload, as JSON text") String payloadJson,
            @ToolParam(required = false, description = "Optional idempotency key: a retry with it returns the same outcome") String idempotencyKey) {
        try {
            var submitted = updates.submit(new ProcessUpdateService.UpdateRequest(processId, businessKey, updateName,
                    payloadJson, idempotencyKey, null, caller()));
            return describe(updates.await(submitted.update(), submitted.timeToWait()).join());
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    @Tool(description = "Apply a named update to the workflow process with a business key, starting a process of the"
            + " definition (with the given initial variables) when there is none — the update is then its first act.")
    public String updateWithStart(
            String workflowDefinitionId,
            String businessKey,
            @ToolParam(description = "The update's name") String updateName,
            @ToolParam(required = false, description = "The payload, as JSON text") String payloadJson,
            @ToolParam(required = false, description = "Initial variables of a process this starts; values are strings") Map<String, String> variables,
            @ToolParam(required = false, description = "Optional idempotency key") String idempotencyKey) {
        try {
            var started = updates.updateWithStart(new ProcessUpdateService.StartRequest(workflowDefinitionId, businessKey,
                    variables, updateName, payloadJson, idempotencyKey, null, caller()));
            var outcome = updates.await(started.update(), started.timeToWait()).join();
            return (started.started() ? "Started process " + outcome.processId() + ". " : "") + describe(outcome);
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    @Tool(description = "List the updates of a workflow process, oldest first: name, who asked, status, result or why it"
            + " was refused, and the payload (sensitive fields masked).")
    public String listProcessUpdates(String processId) {
        try {
            var list = updates.list(processId, caller());
            if (list.isEmpty()) {
                return "Process " + processId + " has had no updates.";
            }
            return list.stream().map(update -> "- " + update.requestedAt() + " " + update.name() + " [" + update.status() + "]"
                            + (update.requestedBy() == null ? "" : " by " + update.requestedBy())
                            + (update.payload() == null ? "" : " payload " + update.payload())
                            + (update.result() == null ? "" : " → " + update.result())
                            + (update.error() == null ? "" : " — " + update.error()))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    private static String describe(ProcessUpdate update) {
        return switch (update.status()) {
            case COMPLETED -> "Update '" + update.name() + "' applied to process " + update.processId() + ". Result: "
                    + update.result();
            case REQUESTED -> "Update '" + update.name() + "' (" + update.id() + ") accepted but not applied yet; it"
                    + " completes durably — list the process's updates to see its outcome.";
            case REFUSED -> "Update '" + update.name() + "' refused: " + update.error();
            case NOT_ACCEPTED -> "Update '" + update.name() + "' not accepted: " + update.error();
            case FAILED -> "Update '" + update.name() + "' failed (nothing changed): " + update.error();
        };
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }
}
