package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpdateStepExecutionUseCase {

    final StepExecutionRepository repository;
    final LogMessageRepository logMessageRepository;

    public void handle(UpdateStepExecutionCommand command) {
        var execution = repository.findById(command.stepId()).orElseThrow();
        execution.updateStatus(command.status());
        repository.save(execution);

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                execution.getProcessId(),
                execution.id(),
                MessageType.Info.name(),
                "Task status changed to " + command.status().name(),
                "x"
        ));

    }

}
