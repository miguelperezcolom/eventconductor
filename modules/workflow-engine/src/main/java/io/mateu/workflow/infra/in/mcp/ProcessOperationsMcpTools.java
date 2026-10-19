package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.Variable;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Operating running processes, as MCP tools: change a live process's variables, complete a step by
 * hand, run a process again from a step, and the incidents — failures grouped by definition, step and
 * error family — with their drill-down and group retry. Collected with the other {@link McpTools} beans.
 *
 * <p>The same application services the REST API and the console use ({@link ProcessOperationsService},
 * {@link IncidentService}), so the same checks, the same authorization (the definition's flow
 * authorization, {@code workflow.security.operations.required-roles}) and the same audit trail. A refused
 * request comes back as a sentence saying why, not as an error, so an assistant can tell the user.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessOperationsMcpTools implements McpTools, McpSystemContext {

    private final ProcessOperationsService processOperations;
    private final IncidentService incidentService;

    /** Who is calling: an MCP endpoint behind Spring Security resolves its caller like any other door. */
    @Autowired(required = false)
    ObjectProvider<CallerResolver> callerResolver;

    @Override
    public String getSystemContext() {
        return """
                Operación manual de procesos en marcha (operaciones de administrador, quedan auditadas con quién, cuándo, qué y por qué — siempre hay que dar un reason):
                - setProcessVariables cambia variables de un proceso RUNNING, PAUSED o ERROR (set escribe, remove borra).
                - completeStepManually da por completado a mano un paso (PENDING, RUNNING, AWAITING_RETRY, ERROR o TIMEOUT) con unas variables de salida, como si su worker hubiera respondido; el flujo sigue. Si era el último fallo de un proceso ERROR, vuelve a RUNNING.
                - retryProcessFromStep vuelve a ejecutar un proceso desde un paso (stepId de la definición): ese paso y todos los que dependen de él vuelven a empezar; el resto queda igual.
                - Se rechazan en procesos terminados (COMPLETED, CANCELLED, COMPENSATED, COMPENSATION_FAILED) y mientras un rollback de saga está en curso.
                - migrateProcess mueve un proceso RUNNING, PAUSED o ERROR a otra versión de su definición (targetVersion; vacío = la actual): cada paso pasa al de mismo id (si el tipo es compatible) o al que diga stepMapping; las tareas en curso se conservan si su contrato no cambió y se re-despachan si cambió; timers y esperas de mensaje se rearman. Antes, dryRunProcessMigration devuelve el plan (qué pasa con cada paso y los problemas: un ERROR lo impide) sin cambiar nada. Para muchos procesos: migrateProcessesInBatch (batch MIGRATE).
                - listIncidents agrupa los fallos (pasos ERROR/TIMEOUT de procesos ERROR) por definición, paso y familia de error; getIncidentProcesses da sus procesos y retryIncident reintenta todo el grupo.
                """;
    }

    @Tool(description = "Change the variables of a live (RUNNING, PAUSED or ERROR) workflow process: 'set' writes"
            + " variables (created or overwritten), 'remove' deletes them by name. A reason is mandatory and is recorded,"
            + " with who asked, in the process's log. A message correlation key or timer computed from a variable follows"
            + " the change. Carried out by the node that owns the process: query it to see the outcome.")
    public String setProcessVariables(
            String processId,
            @ToolParam(required = false, description = "Variables to write, by name; values are strings") Map<String, String> set,
            @ToolParam(required = false, description = "Names of variables to delete") List<String> remove,
            @ToolParam(description = "Why — mandatory, recorded in the audit trail") String reason) {
        return run(() -> processOperations.setVariables(processId, variables(set), remove, reason, caller()),
                "Variable change requested for process " + processId);
    }

    @Tool(description = "Complete one step execution of a live workflow process by hand, as if its worker had answered"
            + " COMPLETED with the given output variables, so the flow carries on past it. The step must be PENDING, RUNNING,"
            + " AWAITING_RETRY, ERROR or TIMEOUT (stepExecutionId comes from getProcessDetails). A step still at a worker"
            + " is told to stop. Refused while a saga rollback is under way. A reason is mandatory and recorded.")
    public String completeStepManually(
            String processId,
            String stepExecutionId,
            @ToolParam(required = false, description = "What the step returns, by name; values are strings") Map<String, String> outputVariables,
            @ToolParam(description = "Why — mandatory, recorded in the audit trail") String reason) {
        return run(() -> processOperations.completeStep(processId, stepExecutionId, variables(outputVariables), reason, caller()),
                "Manual completion of step execution " + stepExecutionId + " requested for process " + processId);
    }

    @Tool(description = "Run a live (RUNNING, PAUSED or ERROR) workflow process again from one of its steps: that step"
            + " (stepId of the definition, not a step execution id) and every step that depends on it go back to the"
            + " start and run again; the rest is left as it is. A failed process runs again. Refused while a saga rollback"
            + " is under way, for a step that has not run yet, and when another failure is not downstream of the step. A"
            + " reason is mandatory and recorded.")
    public String retryProcessFromStep(
            String processId,
            String stepId,
            @ToolParam(description = "Why — mandatory, recorded in the audit trail") String reason) {
        return run(() -> processOperations.retryFromStep(processId, stepId, reason, caller()),
                "Retry from step " + stepId + " requested for process " + processId);
    }

    /** A migration plan, as the tools return it. */
    public record MigrationPlanSummary(String processId, String workflowDefinitionId, int fromVersion, int toVersion,
                                       boolean executable, Map<String, String> mapping, List<String> steps,
                                       List<String> problems) {
    }

    @Tool(description = "Plan moving a live workflow process onto another version of its definition WITHOUT changing"
            + " anything: the effective step mapping (source step id → target step id), what happens to every step"
            + " (REMAP, KEEP_TASK, REDISPATCH, REARM, HISTORY, DROP, CARRY, ADD, ADD_PASSED) and the problems — an ERROR"
            + " means migrateProcess would be refused. targetVersion empty means the definition as it is now.")
    public Object dryRunProcessMigration(
            String processId,
            @ToolParam(required = false, description = "The version to move to; empty for the current one") Integer targetVersion,
            @ToolParam(required = false, description = "Source step id → target step id, over the default (same id)") Map<String, String> stepMapping,
            @ToolParam(required = false, description = "Variables to set as part of the migration, by name") Map<String, String> variableMapping) {
        try {
            var preview = processOperations.dryRunMigration(processId, targetVersion, stepMapping, variableMapping, caller());
            var plan = preview.plan();
            return new MigrationPlanSummary(preview.processId(), preview.workflowDefinitionId(), preview.fromVersion(),
                    preview.toVersion(), plan.executable(), plan.mapping(),
                    plan.steps().stream().map(step -> (step.sourceStepId() == null ? "(new)" : step.sourceStepId()) + " → "
                            + (step.targetStepId() == null ? "(none)" : step.targetStepId()) + ": "
                            + (step.action() == null ? "REFUSED" : step.action().name())
                            + (step.note() == null ? "" : " — " + step.note())).toList(),
                    plan.problems().stream().map(Object::toString).toList());
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    @Tool(description = "Move a live (RUNNING, PAUSED or ERROR) workflow process onto another version of its definition,"
            + " keeping what it has done: each step becomes the target's step of the same id (compatible type) or the one"
            + " stepMapping names; a task in flight is kept if its contract did not change, re-dispatched if it did;"
            + " timers and message waits are re-armed; new steps run when their preconditions hold. Refused (with the"
            + " reasons) when the plan has errors — see dryRunProcessMigration. A reason is mandatory and recorded.")
    public String migrateProcess(
            String processId,
            @ToolParam(required = false, description = "The version to move to; empty for the current one") Integer targetVersion,
            @ToolParam(required = false, description = "Source step id → target step id, over the default (same id)") Map<String, String> stepMapping,
            @ToolParam(required = false, description = "Variables to set as part of the migration, by name") Map<String, String> variableMapping,
            @ToolParam(description = "Why — mandatory, recorded in the audit trail") String reason) {
        return run(() -> processOperations.migrate(processId, targetVersion, stepMapping, variableMapping, reason, caller()),
                "Migration to " + (targetVersion == null ? "the current version" : "v" + targetVersion)
                        + " requested for process " + processId);
    }

    /** One incident, as the tools return it. */
    public record IncidentEntry(String key, String workflowDefinitionId, String stepId, String errorFamily,
                                String sampleError, int failedSteps, int processes, String firstSeen, String lastSeen) {
    }

    /** One failed step of an incident. */
    public record IncidentMember(String processId, String businessKey, String stepExecutionId, String status,
                                 String failedAt, String error) {
    }

    @Tool(description = "List the incidents: the failed steps of failed (ERROR) workflow processes grouped by workflow"
            + " definition, step and error family (the error message with ids and numbers taken out), most failures first,"
            + " with how many steps and processes each holds and when it was first and last seen.")
    public List<IncidentEntry> listIncidents(
            @ToolParam(required = false, description = "Only this workflow definition id's; empty for all") String workflowDefinitionId) {
        return incidentService.incidents(workflowDefinitionId).groups().stream()
                .map(group -> new IncidentEntry(group.key(), group.workflowDefinitionId(), group.stepId(),
                        group.errorFamily(), group.sampleError(), group.failedSteps(), group.processes(),
                        String.valueOf(group.firstSeen()), String.valueOf(group.lastSeen())))
                .toList();
    }

    @Tool(description = "The failed steps (and their processes) of one incident, most recent first — key from listIncidents.")
    public List<IncidentMember> getIncidentProcesses(String key) {
        return incidentService.members(key, 100).stream()
                .map(step -> new IncidentMember(step.processId(), step.businessKey(), step.stepExecutionId(),
                        String.valueOf(step.status()), String.valueOf(step.failedAt()), step.error()))
                .toList();
    }

    @Tool(description = "Retry every failed step of one incident (key from listIncidents), each as a step retry,"
            + " recorded with who asked and the reason in each process's log. It creates a RETRY batch operation over the"
            + " incident, run in the background — follow it with getBatchOperation. A reason is mandatory.")
    public String retryIncident(String key,
                                @ToolParam(description = "Why — mandatory, recorded in the audit trail") String reason) {
        try {
            var outcome = incidentService.retryGroup(key, reason, caller());
            return "Batch operation " + outcome.batchOperationId() + " retries the " + outcome.requested()
                    + " failed step" + (outcome.requested() == 1 ? "" : "s") + " of incident " + key
                    + " in the background; follow it with getBatchOperation.";
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    private String run(Runnable operation, String accepted) {
        try {
            operation.run();
            log.info("{} (MCP)", accepted);
            return accepted + ". It is carried out by the node that owns the process, so query the process to see the outcome.";
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    private static List<Variable> variables(Map<String, String> values) {
        var variables = new ArrayList<Variable>();
        if (values != null) {
            values.forEach((name, value) -> variables.add(new Variable(name, value)));
        }
        return variables;
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }
}
