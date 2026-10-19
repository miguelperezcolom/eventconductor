package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.application.out.ProcessSummaryPage;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON shapes of the processes API ({@code /workflow/api/processes}). They are the API's own
 * records, never the engine's aggregates: an aggregate gains a field whenever the engine needs one,
 * and an API must not change shape for that reason. A field is added here deliberately or not at all.
 *
 * <p>Timestamps are ISO-8601 local date-times ({@code 2026-10-18T09:30:00.123}) in the engine's
 * time zone ({@code workflow.time-zone}), exactly as the engine stores them. Variables are strings,
 * as the engine keeps them; a start may send any JSON value and a non-string one is stored as its JSON.
 */
public final class ProcessApi {

    private ProcessApi() {
    }

    @Schema(name = "StartProcessRequest", description = "What to start.")
    public record StartProcessRequest(
            @Schema(description = "Id of the workflow definition to run.", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "order-fulfilment")
            String definitionId,
            @Schema(description = "Business identifier, unique across processes. Starting again with the same"
                    + " key and definition returns the existing process (200) instead of a second one.",
                    example = "ORDER-1001")
            String businessKey,
            @Schema(description = "Initial process variables. A non-string value is stored as its JSON.")
            Map<String, Object> variables) {
    }

    @Schema(name = "Process", description = "A process, with its variables (a sensitive variable's value reads as ••••).")
    public record ProcessView(
            String id,
            @Schema(description = "Id of the workflow definition it runs.") String definitionId,
            @Schema(description = "Version of the definition it was created from.") int definitionVersion,
            @Schema(description = "The definition's name.") String name,
            String businessKey,
            @Schema(description = "PENDING, RUNNING, PAUSED, COMPLETED, CANCELLED, ERROR, COMPENSATED or COMPENSATION_FAILED.")
            String status,
            @Schema(description = "0–100.") int completionPercentage,
            String created,
            String started,
            String finished,
            @Schema(description = "When it was paused; null unless PAUSED.") String pausedAt,
            @Schema(description = "The PROCESS step execution that spawned it; null for a top-level process.")
            String parentStepExecutionId,
            Map<String, String> variables,
            @Schema(description = "Its search attributes, by name, as the definition declares them (2.30); empty when it declares none.")
            Map<String, String> searchAttributes) {

        static ProcessView of(Process process) {
            return new ProcessView(process.getId(), process.getWorkflowDefinitionId(),
                    process.getWorkflowDefinitionVersion(), process.getName(), process.getBusinessKey(),
                    nameOf(process.getStatus()), process.getCompletionPercentage(),
                    format(process.getCreated()), format(process.getStarted()), format(process.getFinished()),
                    format(process.getPausedAt()), process.getParentStepExecutionId(), variablesOf(process),
                    searchAttributesOf(process));
        }
    }

    @Schema(name = "ProcessSummary", description = "A process as the listing shows it: no variables.")
    /** A process's search attributes as text, by name, in declaration order. */
    static Map<String, String> searchAttributesOf(Process process) {
        var values = new java.util.LinkedHashMap<String, String>();
        io.mateu.workflow.domain.services.SearchAttributeEvaluator.evaluate(process)
                .forEach(value -> values.put(value.name(), value.display()));
        return values;
    }

    public record ProcessSummaryView(
            String id,
            String definitionId,
            String name,
            String businessKey,
            String status,
            int completionPercentage,
            String created,
            String started,
            String finished) {

        static ProcessSummaryView of(ProcessSummary summary) {
            return new ProcessSummaryView(summary.id(), summary.workflowDefinitionId(), summary.name(),
                    summary.businessKey(), nameOf(summary.status()), summary.completionPercentage(),
                    format(summary.created()), format(summary.started()), format(summary.finished()));
        }
    }

    @Schema(name = "ProcessPage", description = "One page of the listing, newest first.")
    public record ProcessPage(
            List<ProcessSummaryView> content,
            @Schema(description = "Zero-based number of the page served — the last real page when the one"
                    + " asked for is past the end.") int page,
            @Schema(description = "Page size applied.") int size,
            @Schema(description = "How many processes match the filter across every page.") long totalElements,
            int totalPages) {

        static ProcessPage of(ProcessSummaryPage found) {
            var size = Math.max(1, found.pageSize());
            return new ProcessPage(found.content().stream().map(ProcessSummaryView::of).toList(),
                    found.pageNumber(), found.pageSize(), found.totalElements(),
                    (int) ((found.totalElements() + size - 1) / size));
        }
    }

    @Schema(name = "StepExecution", description = "One step execution of a process.")
    public record StepView(
            @Schema(description = "Step execution id — what /steps/{stepExecutionId}/retry takes.") String id,
            @Schema(description = "Id of the step in the definition.") String stepId,
            @Schema(description = "CREATED, PENDING, WAITING_ON_LOCK, RUNNING, AWAITING_RETRY, COMPLETED, ERROR, TIMEOUT or CANCELLED.")
            String status,
            @Schema(description = "Position in the process.") long order,
            @Schema(description = "Worker that ran it, when one did.") String workerId,
            @Schema(description = "Attempts made so far.") int attemptCount,
            String startedAt,
            String finishedAt,
            @Schema(description = "When it times out, when it has a deadline.") String deadlineAt,
            @Schema(description = "The message the step is waiting for: a WAIT_FOR_MESSAGE step, or an"
                    + " HTTP_CALL / PUBLISH_EVENT step with awaitReply waiting for its reply.") String awaitingMessageName,
            @Schema(description = "The correlation key that message must carry.") String awaitingCorrelationKey,
            @Schema(description = "For a forEach iteration, the id of the step execution it belongs to (its"
                    + " container); null for every other execution.") String parentExecutionId,
            @Schema(description = "For a forEach iteration, its 0-based position in the list; null otherwise.")
            Integer itemIndex,
            @Schema(description = "The loop pass this execution belongs to: 0 for a step's first run, one more"
                    + " each time a repeat or loopBack runs it again.") int iteration,
            @Schema(description = "True when a later pass of a loop replaced this execution (or a restart"
                    + " discarded it): history, not the step's current execution.") boolean superseded,
            @Schema(description = "For an AGENT_CALL's own execution: its state, turns, tool calls, tokens and last"
                    + " message; null for every other execution. Its turns and tool calls are executions of their"
                    + " own, with this one as parentExecutionId.") Map<String, Object> agent) {

        static StepView of(StepExecution step) {
            return new StepView(step.getId(), step.getStepId(), nameOf(step.getStatus()), step.getOrder(),
                    step.getWorkerId(), step.getAttemptCount(), format(step.getStartedAt()),
                    format(step.getFinishedAt()), format(step.getDeadlineAt()), step.getAwaitingMessageName(),
                    step.getAwaitingCorrelationKey(), step.getParentExecutionId(), step.getItemIndex(),
                    step.getIteration(), step.isSuperseded(),
                    io.mateu.workflow.domain.services.AgentTranscripts.summary(step));
        }
    }

    @Schema(name = "LogEntry", description = "A line of the process's log.")
    public record LogEntryView(
            String timestamp,
            String stepExecutionId,
            @Schema(description = "The message type (INFO, ERROR, …).") String type,
            String message,
            String workerId) {

        static LogEntryView of(LogMessage message) {
            return new LogEntryView(format(message.getTimestamp()), message.getStepExecutionId(),
                    message.getMessageType(), message.getMessage(), message.getWorkerId());
        }
    }

    @Schema(name = "CommandAccepted", description = "A command was accepted (202). It is carried out"
            + " asynchronously by the node that owns the process: read the process to see the outcome.")
    public record CommandAccepted(
            String processId,
            @Schema(description = "cancel, pause, resume, retry, restart, retry-step, set-variables, complete-step,"
                    + " retry-from-step or migrate.") String command,
            @Schema(description = "The step execution, for retry-step and complete-step.") String stepExecutionId,
            @Schema(description = "The definition's step, for retry-from-step.") String stepId) {

        public CommandAccepted(String processId, String command, String stepExecutionId) {
            this(processId, command, stepExecutionId, null);
        }
    }

    @Schema(name = "SetVariablesRequest", description = "A manual change of a live process's variables.")
    public record SetVariablesRequest(
            @Schema(description = "Variables to write — created, or overwritten. A non-string value is stored as its JSON.")
            Map<String, Object> set,
            @Schema(description = "Names of variables to delete.") java.util.List<String> remove,
            @Schema(description = "Why — mandatory, recorded in the process's audit trail.",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "Customer corrected the delivery date by phone")
            String reason) {
    }

    @Schema(name = "CompleteStepRequest", description = "A manual completion of a step execution.")
    public record CompleteStepRequest(
            @Schema(description = "What the step returns — written onto the process as a worker's reply would be."
                    + " A non-string value is stored as its JSON.")
            Map<String, Object> variables,
            @Schema(description = "Why — mandatory, recorded in the process's audit trail.",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "Payment taken by phone, ref 8812")
            String reason) {
    }

    @Schema(name = "OperationReason", description = "The reason a manual operation is performed.")
    public record ReasonRequest(
            @Schema(description = "Why — mandatory, recorded in the process's audit trail.",
                    requiredMode = Schema.RequiredMode.REQUIRED, example = "Partner API fixed; re-run the booking")
            String reason) {
    }

    @Schema(name = "MigrateRequest", description = "A migration of a live process onto another version of its definition.")
    public record MigrateRequest(
            @Schema(description = "The version to move to; absent for the definition as it is now.", example = "5")
            Integer targetVersion,
            @Schema(description = "Source step id → target step id, over the default (the same id, when the target has"
                    + " it with a compatible type). An empty target says the step has no counterpart.")
            Map<String, String> stepMapping,
            @Schema(description = "Variables to set on the process as part of the migration (name → value).")
            Map<String, String> variableMapping,
            @Schema(description = "Why — mandatory for the migration (not for a dry run), recorded in the process's"
                    + " audit trail.", example = "v5 fixes the refund branch")
            String reason) {
    }

    @Schema(name = "MigrationPlan", description = "What a migration does: the mapping, every step's fate and the"
            + " problems. Any ERROR refuses it.")
    public record MigrationPlanView(
            String processId,
            String workflowDefinitionId,
            int fromVersion,
            int toVersion,
            @Schema(description = "Whether no problem refuses it.") boolean executable,
            @Schema(description = "The effective mapping: source step id → target step id. A source step that is absent"
                    + " has no counterpart.") Map<String, String> mapping,
            java.util.List<MigrationStepView> steps,
            java.util.List<MigrationProblemView> problems) {

        public static MigrationPlanView of(io.mateu.workflow.application.services.ProcessOperationsService.MigrationPreview preview) {
            var plan = preview.plan();
            return new MigrationPlanView(preview.processId(), preview.workflowDefinitionId(), preview.fromVersion(),
                    preview.toVersion(), plan.executable(), plan.mapping(),
                    plan.steps().stream().map(step -> new MigrationStepView(step.sourceStepId(), step.targetStepId(),
                            nameOf(step.state()), nameOf(step.action()), step.note())).toList(),
                    plan.problems().stream().map(problem -> new MigrationProblemView(nameOf(problem.severity()),
                            problem.stepId(), problem.message())).toList());
        }
    }

    @Schema(name = "MigrationStep", description = "One step's fate in a migration.")
    public record MigrationStepView(
            @Schema(description = "The step of the version the process runs; absent for a step the target adds.")
            String sourceStepId,
            @Schema(description = "The step it becomes; absent when it has no counterpart.") String targetStepId,
            @Schema(description = "NOT_STARTED, ACTIVE, COMPLETED, SKIPPED or FAILED.") String state,
            @Schema(description = "REMAP, KEEP_TASK, REDISPATCH, REARM, HISTORY, DROP, CARRY, ADD or ADD_PASSED; absent"
                    + " when the step refuses the migration.") String action,
            String note) {
    }

    @Schema(name = "MigrationProblem", description = "A problem of a migration plan.")
    public record MigrationProblemView(
            @Schema(description = "ERROR (refuses) or WARNING.") String severity,
            @Schema(description = "The step it is about, or absent for the process.") String stepId,
            String message) {
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    static String format(LocalDateTime moment) {
        return moment == null ? null : moment.format(ISO);
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * By name, in the order the engine holds them; a repeated name keeps its last value. A sensitive
     * variable's value reads as the mask — see {@code POST /processes/{id}/variables/{name}/reveal}.
     */
    static Map<String, String> variablesOf(Process process) {
        return io.mateu.workflow.application.services.SensitiveVariableMasking.asMap(process);
    }

    @Schema(name = "RevealRequest", description = "Why a sensitive variable's value is needed — recorded in the process's audit trail.")
    public record RevealRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Chargeback investigation CB-1182")
            String reason) {
    }

    @Schema(name = "RevealedVariable", description = "A sensitive variable's value, in clear.")
    public record RevealedVariable(String processId, String name, String value) {
    }
}
