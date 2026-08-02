package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskStatusChangedEventHandler implements DomainEventHandler<TaskStatusChanged> {

    final UpdateStepExecutionUseCase updateStepExecutionUseCase;
    final io.mateu.workflow.infra.in.async.processupstreamevent.UnkeyedEventRouter unkeyedEventRouter;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return TaskStatusChanged.class;
    }

    @Override
    public void handle(TaskStatusChanged e) {
        // A worker that did not echo the process leaves the event unkeyed, so it can land on a
        // pod that does not own the process — the one remaining way two pods reach the same one
        // now that kafka mode has no lock. Send it back out keyed instead of handling it here.
        if (unkeyedEventRouter.rerouted(e)) {
            return;
        }
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(e.taskExecutionId(),
                e.variables().stream()
                        .map(variable -> new Variable(variable.name(), variable.value())).toList(),
                "", StepExecutionStatus.valueOf(e.status().name())));
    }
}
