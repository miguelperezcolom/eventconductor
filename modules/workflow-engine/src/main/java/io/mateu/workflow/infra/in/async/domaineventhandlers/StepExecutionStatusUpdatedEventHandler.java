package io.mateu.workflow.infra.in.async.domaineventhandlers;

import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessStepExecutionUpdateCommand;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepExecutionStatusUpdatedEventHandler implements DomainEventHandler<StepExecutionStatusChanged> {

    final ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;
    final StepOverProcessUseCase stepOverProcessUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return StepExecutionStatusChanged.class;
    }

    @Override
    public void handle(StepExecutionStatusChanged e) {
        processUpdateStepExecutionUpdateUseCase.handle(new ProcessStepExecutionUpdateCommand(e.processId()));
        stepOverProcessUseCase.handle(new StepOverProcessCommand(e.processId()));
    }
}
