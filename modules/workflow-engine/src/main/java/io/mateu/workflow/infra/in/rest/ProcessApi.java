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

    @Schema(name = "Process", description = "A process, with its variables.")
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
            Map<String, String> variables) {

        static ProcessView of(Process process) {
            return new ProcessView(process.getId(), process.getWorkflowDefinitionId(),
                    process.getWorkflowDefinitionVersion(), process.getName(), process.getBusinessKey(),
                    nameOf(process.getStatus()), process.getCompletionPercentage(),
                    format(process.getCreated()), format(process.getStarted()), format(process.getFinished()),
                    format(process.getPausedAt()), process.getParentStepExecutionId(), variablesOf(process));
        }
    }

    @Schema(name = "ProcessSummary", description = "A process as the listing shows it: no variables.")
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
                    + " discarded it): history, not the step's current execution.") boolean superseded) {

        static StepView of(StepExecution step) {
            return new StepView(step.getId(), step.getStepId(), nameOf(step.getStatus()), step.getOrder(),
                    step.getWorkerId(), step.getAttemptCount(), format(step.getStartedAt()),
                    format(step.getFinishedAt()), format(step.getDeadlineAt()), step.getAwaitingMessageName(),
                    step.getAwaitingCorrelationKey(), step.getParentExecutionId(), step.getItemIndex(),
                    step.getIteration(), step.isSuperseded());
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
            @Schema(description = "cancel, pause, resume, retry, restart or retry-step.") String command,
            @Schema(description = "The step execution, for retry-step.") String stepExecutionId) {
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    static String format(LocalDateTime moment) {
        return moment == null ? null : moment.format(ISO);
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** By name, in the order the engine holds them; a repeated name keeps its last value. */
    static Map<String, String> variablesOf(Process process) {
        var variables = new LinkedHashMap<String, String>();
        if (process.getVariables() != null) {
            process.getVariables().forEach(variable -> variables.put(variable.name(), variable.value()));
        }
        return variables;
    }
}
