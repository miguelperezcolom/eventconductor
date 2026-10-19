package io.mateu.workflow.application.usecases.canceltask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.FormExecutionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CancelTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final FormsMetrics formsMetrics;
    final io.mateu.workflow.application.services.HumanTaskEvents humanTaskEvents;

    /**
     * Cancels the open tasks of a step execution the engine no longer waits for (the process was
     * cancelled, the step timed out or escalated elsewhere). A task already completed or cancelled
     * keeps its status: what someone submitted is not rewritten as never having happened.
     */
    public void handle(CancelTaskCommand command) {
        formExecutionRepository.findByStepExecutionId(command.taskId()).stream()
                .filter(io.mateu.workflow.domain.FormExecution::isOpen)
                .forEach(execution -> {
                    var cancelled = execution.withStatus(FormExecutionStatus.CANCELLED);
                    formExecutionRepository.save(cancelled);
                    formsMetrics.taskCancelled(execution.formId(), FormsMetrics.durationOf(execution));
                    humanTaskEvents.changed(cancelled, io.mateu.workflow.dtos.events.integration.HumanTaskChanged.CANCELLED);
                });
    }

}
