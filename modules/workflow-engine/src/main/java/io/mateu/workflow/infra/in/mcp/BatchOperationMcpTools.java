package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Batch operations as MCP tools: apply cancel, retry, restart, pause or resume to every process a filter
 * matches, follow its progress, cancel it. Collected with the other {@link McpTools} beans.
 *
 * <p>Every tool returns a concrete type: Spring AI silently drops a tool returning {@code Object}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchOperationMcpTools implements McpTools, McpSystemContext {

    private final BatchOperationService batches;

    /** Who is calling: each process of the batch is judged against it (flow authorization). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.security.CallerResolver> callerResolver;

    @Override
    public String getSystemContext() {
        return """
                Operaciones masivas (batch operations) del motor:
                - createBatchOperation aplica CANCEL, RETRY, RESTART, PAUSE, RESUME, SET_VARIABLES (setVariables/removeVariables) o RETRY_FROM_STEP (stepId) — estas dos con reason obligatoria (y migrateProcessesInBatch: MIGRATE de los procesos de una definición a otra versión, con un mapeo de pasos común), auditada en cada proceso — a todos los procesos que casan con un filtro (definición, estados, tag, business key, y atributos de búsqueda con query: customerId = "C-9" AND amount > 100). El filtro es obligatorio: nunca actúa sobre todos los procesos por defecto.
                - Se ejecuta en segundo plano, por páginas y con un límite de procesos por segundo; getBatchOperation da el progreso (matched, succeeded, failed, skipped y los motivos de los omitidos: notApplicable por estado, notAuthorized, gone) y una muestra de errores.
                - cancelBatchOperation la detiene: no se toca ningún proceso más; lo hecho, hecho está.
                - Antes de lanzarla, listProcesses con el mismo filtro dice a qué procesos afectará.
                """;
    }

    public record BatchOperationSummary(String id, String type, String status, String filter, long matched,
                                        long succeeded, long failed, long skipped, Map<String, Long> skippedByReason,
                                        int percent, List<String> errors, String createdBy, String createdAt,
                                        String finishedAt, String failure) {

        static BatchOperationSummary of(BatchOperation op) {
            return new BatchOperationSummary(op.id(), op.type().name(), op.status().name(), op.filter().describe(),
                    op.matched(), op.succeeded(), op.failed(), op.skipped(), op.skippedByReason(), op.percent(),
                    op.errors(), op.createdBy(), text(op.createdAt()), text(op.finishedAt()), op.failure());
        }

        private static String text(java.time.LocalDateTime moment) {
            return moment == null ? null : moment.toString();
        }
    }

    @Tool(description = "Apply an operator command to every workflow process a filter matches, in the background:"
            + " type is CANCEL, RETRY (pick ERROR/CANCELLED processes up where they stopped), RESTART (run them again"
            + " from the beginning), PAUSE or RESUME. The filter needs at least one of: workflowDefinitionId, status"
            + " (comma-separated), tag, businessKey (contains), query (search attributes: customerId = \"C-9\" AND"
            + " amount > 100). Also SET_VARIABLES (setVariables name→value and/or removeVariables, comma-separated)"
            + " and RETRY_FROM_STEP (stepId) — both need a reason, recorded with the caller in each process's audit"
            + " trail. Processes the command does not apply to are skipped, not failed. Returns the batch;"
            + " follow it with getBatchOperation.")
    public BatchOperationSummary createBatchOperation(
            @ToolParam(description = "CANCEL, RETRY, RESTART, PAUSE or RESUME") String type,
            @ToolParam(required = false, description = "Only processes of this definition") String workflowDefinitionId,
            @ToolParam(required = false, description = "Comma-separated statuses") String status,
            @ToolParam(required = false, description = "Only processes of the definitions carrying this tag") String tag,
            @ToolParam(required = false, description = "Business key contains this") String businessKey,
            @ToolParam(required = false, description = "Search-attribute conditions: name = value AND name > value") String query,
            @ToolParam(required = false, description = "SET_VARIABLES: variables to set, by name") Map<String, String> setVariables,
            @ToolParam(required = false, description = "SET_VARIABLES: variables to remove, comma-separated") String removeVariables,
            @ToolParam(required = false, description = "RETRY_FROM_STEP: the step id to run again from") String stepId,
            @ToolParam(required = false, description = "Why — mandatory for SET_VARIABLES and RETRY_FROM_STEP") String reason) {
        InputLimits.checkIdentifier(workflowDefinitionId, "workflowDefinitionId");
        InputLimits.checkIdentifier(tag, "tag");
        InputLimits.checkIdentifier(businessKey, "businessKey");
        var filter = new BatchOperationFilter(workflowDefinitionId, tag, statusesOf(status), null, null, businessKey,
                null, false, query);
        log.info("Creating a {} batch operation over {}", type, filter.describe());
        var remove = removeVariables == null ? List.<String>of() : java.util.Arrays.stream(removeVariables.split(","))
                .map(String::strip).filter(name -> !name.isEmpty()).toList();
        InputLimits.checkIdentifier(stepId, "stepId");
        var parameters = io.mateu.workflow.domain.batch.BatchOperationParameters.of(setVariables, remove, stepId, reason);
        return BatchOperationSummary.of(batches.create(typeOf(type), filter, parameters, callerContext()));
    }

    @Tool(description = "Move every live (RUNNING, PAUSED or ERROR) process of one workflow definition onto another"
            + " version of it, in the background — a MIGRATE batch. fromVersion limits it to the processes on that"
            + " version (the others are skipped); status narrows it further (comma-separated). The same stepMapping"
            + " (source step id → target step id) for all; each process is planned on its own and one the plan refuses is"
            + " skipped, not failed. Try dryRunProcessMigration on one first. A reason is mandatory, recorded in each"
            + " process's audit trail. Returns the batch; follow it with getBatchOperation.")
    public BatchOperationSummary migrateProcessesInBatch(
            String workflowDefinitionId,
            @ToolParam(required = false, description = "Only the processes on this version") Integer fromVersion,
            @ToolParam(required = false, description = "The version to move to; empty for the current one") Integer targetVersion,
            @ToolParam(required = false, description = "Comma-separated statuses; empty for RUNNING, PAUSED and ERROR") String status,
            @ToolParam(required = false, description = "Source step id → target step id") Map<String, String> stepMapping,
            @ToolParam(required = false, description = "Variables to set as part of the migration, by name") Map<String, String> variables,
            @ToolParam(description = "Why — mandatory, recorded in each process's audit trail") String reason) {
        InputLimits.checkIdentifier(workflowDefinitionId, "workflowDefinitionId");
        var statuses = statusesOf(status);
        var filter = new BatchOperationFilter(workflowDefinitionId, null, statuses == null || statuses.isEmpty()
                ? java.util.Set.of(io.mateu.workflow.domain.aggregates.ProcessStatus.RUNNING,
                io.mateu.workflow.domain.aggregates.ProcessStatus.PAUSED, io.mateu.workflow.domain.aggregates.ProcessStatus.ERROR)
                : statuses, null, null, null, null, false, null);
        var parameters = io.mateu.workflow.domain.batch.BatchOperationParameters.migrate(targetVersion, fromVersion,
                stepMapping, variables, reason);
        log.info("Creating a MIGRATE batch operation over {}", filter.describe());
        return BatchOperationSummary.of(batches.create(io.mateu.workflow.domain.batch.BatchOperationType.MIGRATE, filter,
                parameters, callerContext()));
    }

    @Tool(description = "List the batch operations, the most recent first, with their progress.")
    public List<BatchOperationSummary> listBatchOperations() {
        return batches.list().stream().map(BatchOperationSummary::of).toList();
    }

    @Tool(description = "Get one batch operation by id: its status (CREATED, RUNNING, COMPLETED, FAILED, CANCELLED),"
            + " progress, skip reasons and a sample of its errors.")
    public BatchOperationSummary getBatchOperation(String batchOperationId) {
        return BatchOperationSummary.of(batches.get(batchOperationId)
                .orElseThrow(() -> new BatchOperationService.BatchOperationNotFoundException(batchOperationId)));
    }

    @Tool(description = "Cancel a batch operation that has not finished: no further process is touched; what was"
            + " already done stays done.")
    public BatchOperationSummary cancelBatchOperation(String batchOperationId) {
        return BatchOperationSummary.of(batches.cancel(batchOperationId));
    }

    private io.mateu.workflow.security.AuthorizationContext callerContext() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    static BatchOperationType typeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("type is required: one of " + java.util.Arrays.toString(BatchOperationType.values()));
        }
        try {
            return BatchOperationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown batch operation type '" + raw + "'; one of "
                    + java.util.Arrays.toString(BatchOperationType.values()));
        }
    }

    static java.util.Set<ProcessStatus> statusesOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var statuses = EnumSet.noneOf(ProcessStatus.class);
        for (var token : raw.split(",")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                statuses.add(ProcessStatus.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown status '" + token.trim() + "'; one of "
                        + java.util.Arrays.toString(ProcessStatus.values()));
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }
}
