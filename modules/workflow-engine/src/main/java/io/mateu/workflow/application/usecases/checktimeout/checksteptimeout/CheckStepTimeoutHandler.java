package io.mateu.workflow.application.usecases.checktimeout.checksteptimeout;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckStepTimeoutHandler {

    final StepExecutionRepository stepExecutionRepository;
    final LogMessageRepository logMessageRepository;
    final ProcessLockService processLockService;
    final WorkflowMetrics workflowMetrics;

    public void handle(CheckStepTimeoutCommand command) {
        var processId = stepExecutionRepository.findById(command.stepExecutionId())
                .orElseThrow().getProcessId();

        if (!processLockService.runExclusively(processId, () -> doHandle(command))) {
            log.debug("Could not acquire lock for process {}, skipping timeout check", processId);
        }
    }

    private void doHandle(CheckStepTimeoutCommand command) {
        // Re-read inside the lock to avoid TOCTOU: another pod may have already updated the status
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

        if (!StepExecutionStatus.PENDING.equals(stepExecution.getStatus())
                && !StepExecutionStatus.RUNNING.equals(stepExecution.getStatus())) {
            return;
        }

        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);

        if (!step.hasTimeLimit() || stepExecution.getStartedAt() == null) {
            return;
        }

        var timeoutAt = stepExecution.currentDeadline();
        if (timeoutAt == null || LocalDateTime.now().isBefore(timeoutAt)) {
            return;
        }
        // Which limit was reached, for the log: the timeout counted from the start, or the deadline.
        var byTimeout = step.timeout() > 0
                && !timeoutAt.isBefore(stepExecution.getStartedAt().plus(step.timeout(), ChronoUnit.MILLIS));

        // A deadline is a business moment, not a per-attempt budget: once reached, a retry would
        // start past it, so the step's retries are not spent on it.
        stepExecution.updateStatus(StepExecutionStatus.TIMEOUT, !byTimeout);
        stepExecutionRepository.save(stepExecution);

        workflowMetrics.stepExecutionFinished(stepExecution.getWorkflowDefinitionId(),
                StepExecutionStatus.TIMEOUT,
                Duration.between(stepExecution.getStartedAt(), LocalDateTime.now()));

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                stepExecution.getProcessId(),
                stepExecution.id(),
                MessageType.Error.name(),
                byTimeout ? "Step timed out after " + Duration.ofMillis(step.timeout())
                        : "Step reached its deadline (" + step.deadline().describe() + ", due " + timeoutAt + ")",
                "system"
        ));
        // Compensation (and retry) is handled centrally by StepExecutionStatusUpdatedEventHandler
        // when it receives the TIMEOUT status change event.
    }

}
