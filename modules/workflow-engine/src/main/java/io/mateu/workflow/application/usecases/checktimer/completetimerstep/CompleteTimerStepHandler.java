package io.mateu.workflow.application.usecases.checktimer.completetimerstep;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompleteTimerStepHandler {

    final StepExecutionRepository stepExecutionRepository;
    final LogMessageRepository logMessageRepository;
    final ProcessLockService processLockService;
    final WorkflowMetrics workflowMetrics;

    public void handle(CompleteTimerStepCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();
        var processId = stepExecution.getProcessId();

        if (!processLockService.tryLock(processId)) {
            log.debug("Could not acquire lock for process {}, skipping timer check", processId);
            return;
        }
        try {
            // Re-read inside the lock to avoid TOCTOU: another pod may have already fired the timer
            stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

            if (!StepExecutionStatus.PENDING.equals(stepExecution.getStatus())) {
                return;
            }

            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);

            if (!StepType.TIMER.equals(step.type()) || stepExecution.getStartedAt() == null) {
                return;
            }

            LocalDateTime dueAt;
            try {
                dueAt = step.timerDueAt(stepExecution.getStartedAt(), stepExecution.getVariables());
            } catch (IllegalArgumentException e) {
                // A misconfigured timer already failed at start(); nothing to fire here.
                return;
            }
            if (LocalDateTime.now().isBefore(dueAt)) {
                return;
            }

            stepExecution.updateStatus(StepExecutionStatus.COMPLETED);
            stepExecutionRepository.save(stepExecution);

            workflowMetrics.stepExecutionFinished(stepExecution.getWorkflowDefinitionId(),
                    StepExecutionStatus.COMPLETED,
                    java.time.Duration.between(stepExecution.getStartedAt(), LocalDateTime.now()));

            logMessageRepository.save(new LogMessage(
                    UUID.randomUUID().toString(),
                    LocalDateTime.now(),
                    stepExecution.getProcessId(),
                    stepExecution.id(),
                    MessageType.Info.name(),
                    "Timer fired, was due at " + dueAt,
                    "system"
            ));
            // The COMPLETED status change event drives the process forward centrally
            // (StepExecutionStatusUpdatedEventHandler → step over).
        } finally {
            processLockService.unlock(processId);
        }
    }

}
