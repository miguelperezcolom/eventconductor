package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * The wire shapes of {@code /workflow/api/batch-operations}: plain records, never the engine's own
 * types, so the API changes only when it is meant to.
 */
public final class BatchOperationApi {

    private BatchOperationApi() {
    }

    @Schema(description = "Which processes a batch acts on — the process listing's filter. At least one field.")
    public record FilterBody(
            @Schema(description = "Only processes of this definition.") String definitionId,
            @Schema(description = "Only processes of the definitions carrying this tag.") String tag,
            @Schema(description = "Only processes in any of these statuses.") List<String> status,
            @Schema(description = "Created at or after: an ISO date-time or date.") String from,
            @Schema(description = "Created at or before: an ISO date-time or date (its last moment).") String to,
            @Schema(description = "Business key contains this (case-insensitive).") String businessKey,
            @Schema(description = "Name or business key contains this (case-insensitive).") String q,
            @Schema(description = "Only ERROR processes.") Boolean onlyErrors,
            @Schema(description = "Search-attribute conditions joined by AND: customerId = \"C-9\" AND amount > 100.")
            String query,
            @Schema(description = "Search attributes by name: exact for a keyword, contains for a text (ANDed with query).")
            Map<String, String> attributes) {
    }

    @Schema(description = "A batch operation to create.")
    public record CreateBatchOperationRequest(
            @Schema(description = "CANCEL, RETRY, RESTART, PAUSE, RESUME, SET_VARIABLES, RETRY_FROM_STEP or MIGRATE.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FilterBody filter,
            @Schema(description = "SET_VARIABLES: the variables to set; MIGRATE: the variables the migration sets — by name"
                    + " (a non-string value is kept as its JSON).")
            Map<String, Object> variables,
            @Schema(description = "SET_VARIABLES: the variables to remove.") List<String> remove,
            @Schema(description = "RETRY_FROM_STEP: the step (a definition step id) to run each process again from.")
            String stepId,
            @Schema(description = "Why — mandatory for SET_VARIABLES, RETRY_FROM_STEP and MIGRATE, recorded in each process's"
                    + " audit trail.")
            String reason,
            @Schema(description = "MIGRATE: the version to move to; absent for the definition as it is now.")
            Integer targetVersion,
            @Schema(description = "MIGRATE: only the processes on this version; the others are skipped as not applicable.")
            Integer fromVersion,
            @Schema(description = "MIGRATE: source step id → target step id, the same for every process.")
            Map<String, String> stepMapping) {

        /** The shape before the manual-operation types: a command and a filter. */
        public CreateBatchOperationRequest(String type, FilterBody filter) {
            this(type, filter, null, null, null, null);
        }

        /** The shape before MIGRATE. */
        public CreateBatchOperationRequest(String type, FilterBody filter, Map<String, Object> variables,
                                           List<String> remove, String stepId, String reason) {
            this(type, filter, variables, remove, stepId, reason, null, null, null);
        }
    }

    @Schema(description = "A batch operation and its progress.")
    public record BatchOperationView(
            String id,
            @Schema(description = "CANCEL, RETRY, RESTART, PAUSE, RESUME, SET_VARIABLES, RETRY_FROM_STEP or MIGRATE.") String type,
            @Schema(description = "CREATED, RUNNING, COMPLETED, FAILED or CANCELLED.") String status,
            @Schema(description = "The filter, as stored (the query normalised).") BatchOperationFilter filter,
            @Schema(description = "The filter in one line.") String filterDescription,
            @Schema(description = "Processes the filter matched when the batch was created (an estimate while it runs; the number visited once it completes).")
            long matched,
            long processed,
            long succeeded,
            long failed,
            long skipped,
            @Schema(description = "Skipped processes by reason: notApplicable (status), notAuthorized, gone.")
            Map<String, Long> skippedByReason,
            @Schema(description = "0–100.") int percent,
            @Schema(description = "A sample of the failures, 'processId: message'.") List<String> errors,
            String createdBy,
            String createdAt,
            String startedAt,
            String finishedAt,
            @Schema(description = "Why the batch itself failed; null otherwise.") String failure,
            @Schema(description = "What the type takes: reason, stepId, set.<name>, remove.<name>; MIGRATE targetVersion,"
                    + " fromVersion, map.<source step>, var.<name>.") Map<String, String> parameters) {

        static BatchOperationView of(BatchOperation op) {
            return new BatchOperationView(op.id(), op.type().name(), op.status().name(), op.filter(),
                    op.filter().describe(), op.matched(), op.processed(), op.succeeded(), op.failed(), op.skipped(),
                    op.skippedByReason(), op.percent(), op.errors(), op.createdBy(), text(op.createdAt()),
                    text(op.startedAt()), text(op.finishedAt()), op.failure(), op.parameters());
        }

        private static String text(java.time.LocalDateTime moment) {
            return moment == null ? null : moment.toString();
        }
    }

    /** The batch type a request names, case-insensitively; an unknown one is a 400. */
    static BatchOperationType type(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("type is required: one of " + java.util.Arrays.toString(BatchOperationType.values()) + ".");
        }
        try {
            return BatchOperationType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown batch operation type '" + raw + "'; one of "
                    + java.util.Arrays.toString(BatchOperationType.values()) + ".");
        }
    }
}
