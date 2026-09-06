package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UnknownProcessException;
import io.mateu.workflow.application.out.UnknownStepExecutionException;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateStepExecutionUseCase {

    private static final java.util.Set<StepExecutionStatus> TERMINAL_STATUSES = java.util.Set.of(
            StepExecutionStatus.COMPLETED,
            StepExecutionStatus.CANCELLED,
            StepExecutionStatus.ERROR,
            StepExecutionStatus.TIMEOUT);

    private static final java.util.Set<StepExecutionStatus> METERED_OUTCOMES = java.util.Set.of(
            StepExecutionStatus.COMPLETED,
            StepExecutionStatus.ERROR,
            StepExecutionStatus.TIMEOUT);

    final StepExecutionRepository repository;
    final LogMessageRepository logMessageRepository;
    final ProcessRepository processRepository;
    final ProcessLockService processLockService;
    final MessageSubscriptionService messageSubscriptionService;
    final WorkflowMetrics workflowMetrics;

    public void handle(UpdateStepExecutionCommand command) {
        var processId = repository.findById(command.stepId())
                .orElseThrow(() -> new UnknownStepExecutionException(command.stepId()))
                .getProcessId();

        // Waiting rather than dropping: silently discarding a worker status update because
        // another node happened to hold the process would leave the step PENDING/RUNNING
        // forever. The wait is the database's row-lock queue now, not a sleep loop.
        if (!processLockService.runExclusively(processId, () -> doUpdate(command))) {
            // The lock queue should block until this attempt owns the process; if it still comes
            // back unacquired (a lost race / rebalance) the worker's result must not be dropped —
            // that would leave the step PENDING/RUNNING forever. Signal a retryable failure so
            // whoever delivered the event redelivers it, exactly as for a lost concurrent write.
            throw new ConcurrentProcessAccessException(processId, null);
        }
    }

    private void doUpdate(UpdateStepExecutionCommand command) {
        var execution = repository.findById(command.stepId())
                .orElseThrow(() -> new UnknownStepExecutionException(command.stepId()));
        if (TERMINAL_STATUSES.contains(execution.getStatus())) {
            // Late report from a worker whose task already reached a final state
            // (timed out, cancelled, …) — applying it would resurrect the step.
            log.warn("Ignoring status update {} for step execution {} already in terminal status {}",
                    command.status(), execution.id(), execution.getStatus());
            return;
        }
        var process = processRepository.findById(execution.getProcessId())
                .orElseThrow(() -> new UnknownProcessException(execution.getProcessId()));
        process.updateVariables(command.variables());
        processRepository.save(process);

        execution.updateStatus(command.status());
        repository.save(execution);

        // The variables just written may be the ones a sibling WAIT_FOR_MESSAGE correlates
        // on, and its stored key has to follow them.
        messageSubscriptionService.rearm(process);

        // The terminal-status guard above makes this idempotent under redelivery.
        if (METERED_OUTCOMES.contains(command.status())) {
            var duration = execution.getStartedAt() != null
                    ? Duration.between(execution.getStartedAt(), LocalDateTime.now()) : null;
            workflowMetrics.stepExecutionFinished(execution.getWorkflowDefinitionId(), command.status(), duration);
        }

        recordWhatHappened(command, execution);
    }

    /**
     * Writes the step's own account of the transition into the process log.
     *
     * <p>{@link UpdateStepExecutionCommand#log()} is what the caller had to say about it — a
     * worker's message, or the exception the engine caught on its behalf when one escaped. It was
     * accepted by the command and then dropped here, every time: the log recorded "Task status
     * changed to ERROR" and nothing about why, so a process that failed carried no trace of the
     * failure and an operator had to go and find the application's stdout.
     *
     * <p>Typed by outcome rather than by who wrote it, so a failure lands where failures are read
     * — the Errors tab, and the graph's hover card — and everything else stays out of it.
     */
    private void recordWhatHappened(UpdateStepExecutionCommand command, StepExecution execution) {
        var reported = command.log() == null ? "" : command.log().trim();
        var failed = StepExecutionStatus.ERROR.equals(command.status())
                || StepExecutionStatus.TIMEOUT.equals(command.status());
        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                execution.getProcessId(),
                execution.id(),
                (failed ? MessageType.Error : MessageType.Info).name(),
                // A failure with nothing said about it still gets a line, so the Errors tab shows
                // that it happened rather than nothing at all.
                reported.isEmpty() ? "Task status changed to " + command.status().name() : reported,
                "system"
        ));
    }
}
