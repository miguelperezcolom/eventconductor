package io.mateu.workflow.infra.in.async.domaineventhandlers;

import io.mateu.workflow.application.usecases.process.start.StartProcessCommand;
import io.mateu.workflow.application.usecases.process.start.StartProcessUseCase;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessCreatedEventHandler implements DomainEventHandler<ProcessCreated> {

    final StartProcessUseCase startProcessUseCase;
    final StepOverProcessUseCase stepOverProcessUseCase;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ProcessCreated.class;
    }

    @Override
    public void handle(ProcessCreated e) {
        startProcessUseCase.handle(new StartProcessCommand(e.processId()));
        stepOverProcessUseCase.handle(new StepOverProcessCommand(e.processId()));
    }
}
