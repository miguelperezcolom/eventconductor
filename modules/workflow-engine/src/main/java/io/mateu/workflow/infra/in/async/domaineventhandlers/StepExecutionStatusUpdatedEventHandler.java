package io.mateu.workflow.infra.in.async.domaineventhandlers;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessStepExecutionUpdateCommand;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionCommand;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepExecutionStatusUpdatedEventHandler implements DomainEventHandler<StepExecutionStatusChanged> {

    final ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final StepExecutionRepository stepExecutionRepository;
    final StreamBridge streamBridge;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return StepExecutionStatusChanged.class;
    }

    @Override
    public void handle(StepExecutionStatusChanged e) {
        var stepExecution = stepExecutionRepository.findById(e.stepExecutionId()).orElseThrow();

        // Auto-retry: if the step failed and the step definition allows more attempts,
        // reset the execution to CREATED and let StepOverProcessUseCase re-dispatch it.
        if (TaskStatus.ERROR.equals(e.status())) {
            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
            if (stepExecution.getAttemptCount() < step.retries()) {
                stepExecution.scheduleRetry();
                stepExecutionRepository.save(stepExecution);
                stepOverProcessUseCase.handle(new StepOverProcessCommand(stepExecution.getProcessId()));
                return;
            }
        }

        processUpdateStepExecutionUpdateUseCase.handle(new ProcessStepExecutionUpdateCommand(stepExecution.getProcessId()));
        stepOverProcessUseCase.handle(new StepOverProcessCommand(stepExecution.getProcessId()));
        if (TaskStatus.TIMEOUT.equals(e.status())) {
            streamBridge.send("downstream", new TaskCancellationRequested(e.stepExecutionId()));
        }
    }
}
