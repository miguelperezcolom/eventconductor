package io.mateu.workflow.infra.in.async.processupstreamevent.upstreameventhandlers;

import io.mateu.workflow.application.usecases.process.inject.InjectStepsCommand;
import io.mateu.workflow.application.usecases.process.inject.InjectStepsUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepsInjectedEventHandler implements DomainEventHandler<StepsInjected> {

    final InjectStepsUseCase injectStepsUseCase;
    final io.mateu.workflow.infra.in.async.processupstreamevent.UnkeyedEventRouter unkeyedEventRouter;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return StepsInjected.class;
    }

    @Override
    public void handle(StepsInjected e) {
        // Single-writer routing: send the event back out keyed if it arrived unkeyed on a pod that
        // does not own the process. A DYNAMIC step always echoes its process, so this never fires
        // for a well-formed reply — kept for symmetry with the other upstream handlers.
        if (unkeyedEventRouter.rerouted(e)) {
            return;
        }
        injectStepsUseCase.handle(new InjectStepsCommand(e.taskExecutionId(), e.stepsJson()));
    }
}
