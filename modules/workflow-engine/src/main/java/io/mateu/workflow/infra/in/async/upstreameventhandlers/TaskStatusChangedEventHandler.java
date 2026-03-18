package io.mateu.workflow.infra.in.async.upstreameventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskStatusChangedEventHandler implements DomainEventHandler<TaskStatusChanged> {

    final UpdateStepExecutionUseCase updateStepExecutionUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TaskStatusChanged.class;
    }

    @Override
    public void handle(TaskStatusChanged e) {
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(e.taskExecutionId(), Map.of(), "", StepExecutionStatus.valueOf(e.status().name())));
    }
}
