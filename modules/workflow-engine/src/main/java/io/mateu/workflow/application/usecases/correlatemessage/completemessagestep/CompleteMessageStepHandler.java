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

    public void handle(CompleteMessageStepCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();
        var processId = stepExecution.getProcessId();

        if (!processLockService.tryLock(processId)) {
            log.debug("Could not acquire lock for process {}, skipping message correlation", processId);
            return;
        }
        try {
            // Re-read inside the lock to avoid TOCTOU: another pod (or a duplicate
            // delivery of the same message) may have already completed the step.
            stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

            if (!StepExecutionStatus.PENDING.equals(stepExecution.getStatus())) {
                return;
            }

            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);

            if (!StepType.WAIT_FOR_MESSAGE.equals(step.type()) || stepExecution.getStartedAt() == null
                    || !command.messageName().equals(step.messageName())) {
                return;
            }

            var process = processRepository.findById(processId).orElseThrow();
            if (!MessageCorrelation.matches(step, process, command.correlationKey())) {
                return;
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
        } finally {
            processLockService.unlock(processId);
        }
    }

}
