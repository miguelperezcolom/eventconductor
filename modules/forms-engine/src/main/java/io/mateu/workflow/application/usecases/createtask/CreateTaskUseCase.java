package io.mateu.workflow.application.usecases.createtask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.services.HumanTaskEvents;
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
    final FormsMetrics formsMetrics;
    final HumanTaskEvents humanTaskEvents;

    public void handle(CreateTaskCommand command) {
        var task = new FormExecution(
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
        );
        formExecutionRepository.save(task);
        formsMetrics.taskCreated(command.formId());
        humanTaskEvents.changed(task);
    }

}
