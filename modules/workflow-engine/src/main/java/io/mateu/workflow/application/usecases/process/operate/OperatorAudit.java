package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The audit trail of the manual operations an operator performs on a live process — set its
 * variables, complete a step for it, run it again from a step, retry an incident group.
 *
 * <p>Every one of them leaves a line in the process's own log, so it is read where everything else
 * about the process is read (its detail page, {@code GET /workflow/api/processes/{id}/logs}, the
 * {@code getProcessLogs} MCP tool): <b>who</b> did it — the {@code workerId} of the line is
 * {@code operator:<subject>}, the subject the caller was identified as at the door — <b>when</b> (the
 * line's timestamp), <b>what changed</b> and <b>why</b> (the reason the operator had to give). The
 * process detail page lists these lines in its Operations tab. Each one also counts on
 * {@code eventconductor.processes.operations{operation}}.
 *
 * <p>A refused operation — the process moved on between the request and its turn under the lock —
 * leaves a line too, so the operator who asked can see that it was not carried out, and why. Both are
 * written as {@code Info}: an operator's action is not a failure of the process, and an error line
 * would be read as one (the Errors tab, the incident grouping).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorAudit {

    /** The {@code workerId} prefix that marks a log line as an operator's manual operation. */
    public static final String OPERATOR_PREFIX = "operator:";

    /** Who a request with no identified caller is recorded as. */
    public static final String ANONYMOUS = "anonymous";

    /** How much of a variable value an audit line quotes. */
    static final int MAX_QUOTED = 200;

    public static final String SET_VARIABLES = "set-variables";
    public static final String COMPLETE_STEP = "complete-step";
    public static final String RETRY_FROM_STEP = "retry-from-step";
    public static final String RETRY_INCIDENT = "retry-incident";

    final LogMessageRepository logMessageRepository;
    final WorkflowMetrics workflowMetrics;

    /** Whether a log line was written by an operator's manual operation. */
    public static boolean isOperatorLine(LogMessage line) {
        return line != null && line.getWorkerId() != null && line.getWorkerId().startsWith(OPERATOR_PREFIX);
    }

    /** {@code operator:<subject>} — the log line's author. */
    public static String author(String requestedBy) {
        return OPERATOR_PREFIX + (requestedBy == null || requestedBy.isBlank() ? ANONYMOUS : requestedBy);
    }

    /** Records an operation that was carried out, and counts it. */
    public void performed(Process process, String stepExecutionId, String operation, String requestedBy,
                          String reason, String what) {
        performed(process.getId(), process.getWorkflowDefinitionId(), stepExecutionId, operation, requestedBy,
                reason, what);
    }

    /** {@link #performed(Process, String, String, String, String, String)}, by the process's id. */
    public void performed(String processId, String workflowDefinitionId, String stepExecutionId, String operation,
                          String requestedBy, String reason, String what) {
        write(processId, stepExecutionId, requestedBy,
                "[" + operation + "] by " + who(requestedBy) + ": " + what + reasonSuffix(reason));
        workflowMetrics.processOperation(workflowDefinitionId, operation);
        log.info("Manual operation {} on process {} by {}: {}", operation, processId, who(requestedBy), what);
    }

    /** Records an operation that was refused once it reached the process, and why. */
    public void refused(String processId, String stepExecutionId, String operation, String requestedBy,
                        String reason, String why) {
        write(processId, stepExecutionId, requestedBy,
                "[" + operation + "] by " + who(requestedBy) + " NOT carried out: " + why + reasonSuffix(reason));
        log.warn("Manual operation {} on process {} by {} refused: {}", operation, processId, who(requestedBy), why);
    }

    private void write(String processId, String stepExecutionId, String requestedBy, String line) {
        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                processId,
                stepExecutionId,
                MessageType.Info.name(),
                line,
                author(requestedBy)));
    }

    private static String who(String requestedBy) {
        return requestedBy == null || requestedBy.isBlank() ? ANONYMOUS : requestedBy;
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : " — reason: " + reason.strip();
    }

    /** A value as an audit line quotes it: bounded, and null spelled out. */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_QUOTED ? "'" + value + "'" : "'" + value.substring(0, MAX_QUOTED) + "…'";
    }
}
