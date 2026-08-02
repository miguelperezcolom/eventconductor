package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.services.ProcessLocks;
import io.mateu.workflow.domain.aggregates.LogMessage;
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

    // Optional: present in jpa mode, absent in memory mode.
    // When null, work runs outside an explicit transaction.
    @Autowired(required = false)
    private TransactionTemplate transactionTemplate;

    public void handle(UpdateStepExecutionCommand command) {
        var processId = repository.findById(command.stepId()).orElseThrow().getProcessId();

        // Retry instead of dropping: silently discarding a worker status update on a busy
        // lock would leave the step PENDING/RUNNING forever.
        if (!ProcessLocks.lockWithRetry(processLockService, processId)) {
            log.error("Could not acquire lock for process {}, status update {} for step {} was NOT applied",
                    processId, command.status(), command.stepId());
            return;
        }
        try {
            log.debug("Lock acquired for process {}", processId);
            doUpdate(command);
        } catch (Exception e) {
            log.error("Error updating step execution", e);
        } finally {
            processLockService.unlock(processId);
            log.debug("Lock released for process {}", processId);
        }
    }

    private void doUpdate(UpdateStepExecutionCommand command) {
        Runnable work = () -> {
            var execution = repository.findById(command.stepId()).orElseThrow();
            if (TERMINAL_STATUSES.contains(execution.getStatus())) {
                // Late report from a worker whose task already reached a final state
                // (timed out, cancelled, …) — applying it would resurrect the step.
                log.warn("Ignoring status update {} for step execution {} already in terminal status {}",
                        command.status(), execution.id(), execution.getStatus());
                return;
            }
            var process = processRepository.findById(execution.getProcessId()).orElseThrow();
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

            logMessageRepository.save(new LogMessage(
                    UUID.randomUUID().toString(),
                    LocalDateTime.now(),
                    execution.getProcessId(),
                    execution.id(),
                    MessageType.Info.name(),
                    "Task status changed to " + command.status().name(),
                    "system"
            ));
        };

        if (transactionTemplate != null) {
            transactionTemplate.execute(status -> { work.run(); return null; });
        } else {
            work.run();
        }
    }
}
