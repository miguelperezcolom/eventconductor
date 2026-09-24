package io.mateu.workflow.application.sync;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.SyncInvocation;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.CompensationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * The answer the engine gives on a process's behalf when the process reaches an end without having
 * replied — the failure contract of synchronous invocation.
 *
 * <p>Applied to every process save, just before it is written ({@code ProcessRepository.save} is the
 * one point every status transition funnels through), so the answer commits with the transition
 * that decided it, whichever use case made it — the step-over, the saga rollback handler, the
 * status recompute, a cancellation. It only ever acts on a sync-invocable definition (read from the
 * process's own definition snapshot) that has not replied yet, and a process replies once.
 *
 * <table>
 *   <tr><th>status reached</th><th>REPLY_IMMEDIATELY</th><th>REPLY_AFTER_COMPENSATION</th></tr>
 *   <tr><td>ERROR</td><td>FAILED, compensation IN_PROGRESS (or NONE if nothing to undo)</td>
 *       <td>FAILED/NONE only if there is nothing to undo; otherwise wait</td></tr>
 *   <tr><td>COMPENSATED</td><td colspan=2>COMPENSATED/DONE</td></tr>
 *   <tr><td>COMPENSATION_FAILED</td><td colspan=2>COMPENSATION_FAILED/FAILED</td></tr>
 *   <tr><td>CANCELLED</td><td colspan=2>CANCELLED</td></tr>
 *   <tr><td>COMPLETED</td><td colspan=2>COMPLETED_WITHOUT_REPLY (a path with no REPLY step)</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EngineReplyPolicy {

    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final java.util.regex.Pattern DECLARES_SYNC_INVOCATION =
            java.util.regex.Pattern.compile("\"syncInvocation\"\\s*:\\s*\\{");

    final StepExecutionRepository stepExecutionRepository;
    final LogMessageRepository logMessageRepository;
    final CompensationService compensationService;

    /** Records the engine's answer on {@code process} if this save is where one is due. */
    public void apply(Process process) {
        if (process == null || process.hasReplied() || !isEnd(process.getStatus())) {
            return;
        }
        var sync = syncInvocationOf(process);
        if (sync == null || !sync.enabled()) {
            return;
        }
        var reply = switch (process.getStatus()) {
            case ERROR -> onError(process, sync);
            case COMPENSATED -> ProcessReply.of(ProcessReply.Outcome.COMPENSATED,
                    ProcessReply.Compensation.DONE, failureOf(process));
            case COMPENSATION_FAILED -> ProcessReply.of(ProcessReply.Outcome.COMPENSATION_FAILED,
                    ProcessReply.Compensation.FAILED, failureOf(process));
            case CANCELLED -> ProcessReply.of(ProcessReply.Outcome.CANCELLED, ProcessReply.Compensation.NONE,
                    "The process was cancelled before it replied.");
            case COMPLETED -> ProcessReply.of(ProcessReply.Outcome.COMPLETED_WITHOUT_REPLY,
                    ProcessReply.Compensation.NONE, null);
            default -> null;
        };
        if (reply != null && process.recordReply(reply)) {
            log.info("Process {} answered its synchronous caller on its behalf: {} (compensation {})",
                    process.getId(), reply.outcome(), reply.compensation());
        }
    }

    private ProcessReply onError(Process process, SyncInvocation sync) {
        var decision = compensationService.decide(stepExecutionRepository.findByProcessId(process.getId()));
        var compensation = switch (decision.outcome()) {
            case RUN, WAITING -> ProcessReply.Compensation.IN_PROGRESS;
            case DONE -> ProcessReply.Compensation.DONE;
            case FAILED -> ProcessReply.Compensation.FAILED;
            case NONE -> ProcessReply.Compensation.NONE;
        };
        if (sync.onFailure() == SyncInvocation.FailurePolicy.REPLY_AFTER_COMPENSATION
                && compensation != ProcessReply.Compensation.NONE) {
            // Answered when the rollback ends, by the save that marks it COMPENSATED or
            // COMPENSATION_FAILED. Not here even once the decision reads DONE or FAILED: the status
            // recompute saves the process ERROR once more just before that mark, and answering then
            // would call a finished rollback a plain failure.
            return null;
        }
        return ProcessReply.of(ProcessReply.Outcome.FAILED, compensation, failureOf(process));
    }

    /** "Step 'x' (Name) failed: last error line", for the step whose failure stopped the process. */
    String failureOf(Process process) {
        try {
            var failed = stepExecutionRepository.findByProcessId(process.getId()).stream()
                    .filter(EngineReplyPolicy::isBlockingFailure)
                    .min(Comparator.comparing(StepExecution::getFinishedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (failed == null) {
                return "The process failed.";
            }
            var step = pojoFromJson(failed.getStepJson(), Step.class);
            var message = logMessageRepository.findByProcessId(process.getId()).stream()
                    .filter(log -> failed.id().equals(log.getStepExecutionId()))
                    .filter(log -> "Error".equalsIgnoreCase(log.getMessageType()))
                    .filter(log -> log.getMessage() != null && !log.getMessage().isBlank())
                    .max(Comparator.comparing(LogMessage::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(LogMessage::getMessage)
                    .orElse(failed.getStatus() == StepExecutionStatus.TIMEOUT ? "timed out" : "ended in error");
            var summary = "Step '" + failed.getStepId() + "'"
                    + (step.name() == null || step.name().isBlank() ? "" : " (" + step.name() + ")")
                    + " failed: " + message;
            return summary.length() > MAX_ERROR_LENGTH ? summary.substring(0, MAX_ERROR_LENGTH) : summary;
        } catch (Exception e) {
            return "The process failed.";
        }
    }

    private static boolean isBlockingFailure(StepExecution execution) {
        if (execution.getStatus() == StepExecutionStatus.ERROR) {
            return true;
        }
        if (execution.getStatus() == StepExecutionStatus.TIMEOUT) {
            var step = pojoFromJson(execution.getStepJson(), Step.class);
            return step.onTimeoutStepId() == null || step.onTimeoutStepId().isBlank();
        }
        return false;
    }

    private static boolean isEnd(ProcessStatus status) {
        return status == ProcessStatus.ERROR || status == ProcessStatus.COMPENSATED
                || status == ProcessStatus.COMPENSATION_FAILED || status == ProcessStatus.CANCELLED
                || status == ProcessStatus.COMPLETED;
    }

    /** The sync configuration the process runs under — from its own definition snapshot. */
    public static SyncInvocation syncInvocationOf(Process process) {
        var json = process.getWorkflowDefinitionJson();
        // A cheap textual test before the parse, which runs on every terminal save: a snapshot that
        // serializes the field as null — every definition that is not sync-invocable — must not pay
        // for parsing a whole definition.
        if (json == null || json.isBlank() || !DECLARES_SYNC_INVOCATION.matcher(json).find()) {
            return null;
        }
        try {
            return pojoFromJson(json, WorkflowDefinition.class).syncInvocation();
        } catch (Exception e) {
            return null;
        }
    }
}
