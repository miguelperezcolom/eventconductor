package io.mateu.workflow.application.usecases.canceltask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CancelTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final FormExecutionEntityRepository formExecutionEntityRepository;
    final FormsMetrics formsMetrics;
    final io.mateu.workflow.application.services.HumanTaskEvents humanTaskEvents;

    public void handle(CancelTaskCommand command) {
        var formExecutions = formExecutionEntityRepository.findByStepExecutionId(command.taskId());
        formExecutions.forEach(entity -> {
            var formExecution = formExecutionRepository.findById(entity.getId());
            formExecution.ifPresent(execution -> {
                var cancelled = execution.withStatus(FormExecutionStatus.CANCELLED);
                formExecutionRepository.save(cancelled);
                formsMetrics.taskCancelled(execution.formId(), FormsMetrics.durationOf(execution));
                humanTaskEvents.changed(cancelled);
            });
        });
    }

}
