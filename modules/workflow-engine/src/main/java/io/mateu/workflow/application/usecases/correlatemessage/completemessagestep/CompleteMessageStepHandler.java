package io.mateu.workflow.application.usecases.correlatemessage.completemessagestep;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.domain.services.MessageCorrelation;
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
public class CompleteMessageStepHandler {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final LogMessageRepository logMessageRepository;
    final ProcessLockService processLockService;
    final MessageSubscriptionService messageSubscriptionService;
    final WorkflowMetrics workflowMetrics;

    /** Completes the step if it is still waiting for this message; whether it did. */
    public boolean handle(CompleteMessageStepCommand command) {
        var processId = stepExecutionRepository.findById(command.stepExecutionId())
                .orElseThrow().getProcessId();

        // Not skipped when the lock cannot be had: nothing re-scans for a message the way the
        // scheduler re-scans timers, so dropping it here would lose the message for good and leave
        // the step waiting forever. Failing makes the delivery come round again; doHandle is
        // idempotent under redelivery.
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        ProcessLockService.runExclusivelyOrThrow(processLockService, processId, () -> completed.set(doHandle(command)));
        return completed.get();
    }

    private boolean doHandle(CompleteMessageStepCommand command) {
        // Re-read inside the lock to avoid TOCTOU: another pod (or a duplicate
        // delivery of the same message) may have already completed the step.
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

        if (!StepExecutionStatus.PENDING.equals(stepExecution.getStatus())) {
            return false;
        }

        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);

        if (!StepType.WAIT_FOR_MESSAGE.equals(step.type()) || stepExecution.getStartedAt() == null
                || !command.messageName().equals(step.messageName())) {
            return false;
        }

        var process = processRepository.findById(stepExecution.getProcessId()).orElseThrow();
        if (!MessageCorrelation.matches(step, process, command.correlationKey())) {
            return false;
        }

        // The message payload becomes process state before the step completes, so
        // successors already see it when the COMPLETED event steps the flow over.
        process.updateVariables(command.variables());
        processRepository.save(process);

        stepExecution.updateStatus(StepExecutionStatus.COMPLETED);
        stepExecutionRepository.save(stepExecution);

        // The message payload became process state above; a sibling WAIT_FOR_MESSAGE may
        // correlate on one of those variables. Rearm after saving this step, so the one
        // just completed is already out of the waiting set.
        messageSubscriptionService.rearm(process);

        workflowMetrics.stepExecutionFinished(stepExecution.getWorkflowDefinitionId(),
                StepExecutionStatus.COMPLETED,
                java.time.Duration.between(stepExecution.getStartedAt(), LocalDateTime.now()));

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                stepExecution.getProcessId(),
                stepExecution.id(),
                MessageType.Info.name(),
                "Message '" + command.messageName() + "' correlated with key '" + command.correlationKey() + "'",
                "system"
        ));
        // The COMPLETED status change event drives the process forward centrally
        // (StepExecutionStatusUpdatedEventHandler → step over).
        return true;
    }

}
