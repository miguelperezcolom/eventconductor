package io.mateu.workflow.application.usecases.stepexecution.checktimeout;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
public class CheckTimeoutUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final LogMessageRepository logMessageRepository;

    public void handle(CheckTimeoutCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

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

        stepExecution.updateStatus(StepExecutionStatus.ERROR);
        stepExecutionRepository.save(stepExecution);

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                stepExecution.getProcessId(),
                stepExecution.id(),
                MessageType.Error.name(),
                "Step timed out after " + step.timeout() + "ms",
                "system"
        ));

        if (step.rollbackable() && step.compensationStepId() != null && !step.compensationStepId().isBlank()) {
            var process = processRepository.findById(stepExecution.getProcessId()).orElseThrow();
            stepExecutionRepository.findByProcess(process).stream()
                    .filter(se -> step.compensationStepId().equals(se.getStepId()))
                    .filter(se -> StepExecutionStatus.CREATED.equals(se.getStatus()))
                    .findFirst()
                    .ifPresent(compensation -> {
                        compensation.start(process.getVariables());
                        stepExecutionRepository.save(compensation);
                    });
        }
    }

}
