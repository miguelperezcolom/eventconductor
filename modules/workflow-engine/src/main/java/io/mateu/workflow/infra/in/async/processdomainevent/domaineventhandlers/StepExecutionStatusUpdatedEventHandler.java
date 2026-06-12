package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessStepExecutionUpdateCommand;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
public class StepExecutionStatusUpdatedEventHandler implements DomainEventHandler<StepExecutionStatusChanged> {

    final ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final DownstreamEventPublisher downstreamEventPublisher;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return StepExecutionStatusChanged.class;
    }

    @Override
    public void handle(StepExecutionStatusChanged e) {
        var stepExecution = stepExecutionRepository.findById(e.stepExecutionId()).orElseThrow();

        if (TaskStatus.ERROR.equals(e.status()) || TaskStatus.TIMEOUT.equals(e.status())) {
            // Cancel the worker for timed-out tasks regardless of whether we retry.
            if (TaskStatus.TIMEOUT.equals(e.status())) {
                downstreamEventPublisher.publish(new TaskCancellationRequested(e.stepExecutionId()));
            }

            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);

            // Auto-retry: attempts remaining → reset to CREATED and re-dispatch.
            if (stepExecution.getAttemptCount() < step.retries()) {
                stepExecution.scheduleRetry();
                stepExecutionRepository.save(stepExecution);
                stepOverProcessUseCase.handle(new StepOverProcessCommand(stepExecution.getProcessId()));
                return;
            }

            // Retries exhausted: start compensation step if the step is rollbackable.
            triggerCompensation(stepExecution, step);
        }

        processUpdateStepExecutionUpdateUseCase.handle(new ProcessStepExecutionUpdateCommand(stepExecution.getProcessId()));
        stepOverProcessUseCase.handle(new StepOverProcessCommand(stepExecution.getProcessId()));
    }

    /**
     * Starts the compensation step (if configured) when a step has exhausted all its
     * retries.  The compensation step is a regular StepExecution already created at
     * process-start time; we just call start() on it so it gets dispatched.
     */
    private void triggerCompensation(StepExecution stepExecution, Step step) {
        if (!step.rollbackable()
                || step.compensationStepId() == null
                || step.compensationStepId().isBlank()) {
            return;
        }
        var process = processRepository.findById(stepExecution.getProcessId()).orElseThrow();
        stepExecutionRepository.findByProcess(process).stream()
                .filter(se -> step.compensationStepId().equals(se.getStepId()))
                .filter(se -> StepExecutionStatus.CREATED.equals(se.getStatus()))
                .findFirst()
                .ifPresent(compensation -> {
                    compensation.start(process.getVariables());
                    stepExecutionRepository.save(compensation);
                });
    }
}
