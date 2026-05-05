package io.mateu.workflow.application.usecases.createtask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateTaskUseCase {

    final FormExecutionRepository formExecutionRepository;

    public void handle(CreateTaskCommand command) {
        formExecutionRepository.save(new FormExecution(
                UUID.randomUUID().toString(),
                command.formId(),
                command.processId(),
                command.stepId(),
                command.stepExecutionId(),
                FormExecutionStatus.PENDING,
                null,
                null,
                command.variables(),
                List.of()
        ));
    }

}
