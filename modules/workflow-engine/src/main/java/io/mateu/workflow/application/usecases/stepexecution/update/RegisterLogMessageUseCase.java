package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegisterLogMessageUseCase {

    final StepExecutionRepository repository;
    final LogMessageRepository logMessageRepository;
    final ProcessRepository processRepository;

    public void handle(RegisterLogMessageCommand command) {
        var execution = repository.findById(command.stepId()).orElseThrow();

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                execution.getProcessId(),
                execution.id(),
                command.messageType().name(),
                command.message(),
                "x"
        ));

    }

}
