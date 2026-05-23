package io.mateu.workflow.application.usecases.checktimeout.checksteptimeout;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.StepExecutionRepository;
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

    public void handle(CheckStepTimeoutCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();
        var processId = stepExecution.getProcessId();

        if (!processLockService.tryLock(processId)) {
            log.debug("Could not acquire lock for process {}, skipping timeout check", processId);
            return;
        }
        try {
            // Re-read inside the lock to avoid TOCTOU: another pod may have already updated the status
            stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

            if (!StepExecutionStatus.PENDING.equals(stepExecution.getStatus())
                    && !StepExecutionStatus.RUNNING.equals(stepExecution.getStatus())) {
                return;
            }

            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);

            if (step.timeout() <= 0 || stepExecution.getStartedAt() == null) {
                return;
            }

            var timeoutAt = stepExecution.getStartedAt().plus(step.timeout(), ChronoUnit.MILLIS);
            if (LocalDateTime.now().isBefore(timeoutAt)) {
                return;
            }

            stepExecution.updateStatus(StepExecutionStatus.TIMEOUT);
            stepExecutionRepository.save(stepExecution);

            logMessageRepository.save(new LogMessage(
                    UUID.randomUUID().toString(),
                    LocalDateTime.now(),
                    stepExecution.getProcessId(),
                    stepExecution.id(),
                    MessageType.Error.name(),
                    "Step timed out after " + Duration.ofMillis(step.timeout()),
                    "system"
            ));
            // Compensation (and retry) is handled centrally by StepExecutionStatusUpdatedEventHandler
            // when it receives the TIMEOUT status change event.
        } finally {
            processLockService.unlock(processId);
        }
    }

}
