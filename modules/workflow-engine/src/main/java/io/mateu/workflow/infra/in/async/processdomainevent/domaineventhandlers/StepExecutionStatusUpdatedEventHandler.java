package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessStepExecutionUpdateCommand;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
public class StepExecutionStatusUpdatedEventHandler implements DomainEventHandler<StepExecutionStatusChanged> {

    final ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final DownstreamEventPublisher downstreamEventPublisher;
    final WorkflowMetrics workflowMetrics;
    final CancelChildProcessService cancelChildProcessService;
    final CompensationService compensationService;

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
                workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                        WorkflowMetrics.RetryTrigger.AUTO);
                stepOverProcessUseCase.handle(new StepOverProcessCommand(stepExecution.getProcessId()));
                return;
            }

            // Retries exhausted on a PROCESS step (ERROR from the worker pipeline or TIMEOUT
            // from the timeout scheduler — both saved before this event): its still-running
            // child process must be cancelled. Not done while retries remain, because a
            // retried PROCESS step re-attaches to the same running child.
            cancelChildProcessService.stepReachedTerminalStatus(stepExecution);
        }

        // Load the process's steps once and reuse them for the status recompute and the saga
        // rollback decision — neither mutates the step set, so a single terminal event no longer
        // reloads it twice over. Step-over deliberately reloads under its own lock: it is the one
        // that dispatches, so it must read the authoritative view.
        var processId = stepExecution.getProcessId();
        var process = processRepository.findById(processId).orElseThrow();
        var executions = stepExecutionRepository.findByProcess(process);

        processUpdateStepExecutionUpdateUseCase.handle(new ProcessStepExecutionUpdateCommand(processId), executions);
        // Drive saga rollback before stepping the process over: a failed process starts (or
        // continues) compensating executed steps in reverse order here; step-over then just
        // sees the blocking error and holds the normal flow.
        advanceCompensation(processId, executions);
        stepOverProcessUseCase.handle(new StepOverProcessCommand(processId));
    }

    /**
     * Advances process-level saga rollback. When the process has finally failed, the
     * compensations of every executed rollbackable step run sequentially in reverse execution
     * order: the failing event starts the first (latest-executed) compensation, and each
     * compensation's own completion event starts the next. Called on every terminal event and
     * kept idempotent by deriving the next action purely from persisted state
     * ({@link CompensationService}), so redelivery and restarts are safe.
     */
    private void advanceCompensation(String processId, List<StepExecution> executions) {
        // Reload the process (not the steps): the status recompute just saved it, so a fresh read
        // carries the current @Version — reusing the pre-update snapshot here would save a stale
        // version and trip optimistic locking. The step list is unchanged, so it is reused.
        var process = processRepository.findById(processId).orElseThrow();
        var decision = compensationService.decide(executions);
        switch (decision.outcome()) {
            case RUN -> {
                // The compensation step is a regular StepExecution created at process-start
                // time (CREATED); start() dispatches it. Only one is ever in flight, so the
                // reverse-order chain advances one completion at a time.
                var compensation = decision.next();
                compensation.start(process);
                stepExecutionRepository.save(compensation);
                workflowMetrics.compensationTriggered(compensation.getWorkflowDefinitionId());
            }
            case DONE -> markCompensated(process);
            case NONE, WAITING, FAILED -> { /* nothing to start now */ }
        }
    }

    /**
     * Marks a fully rolled-back process COMPENSATED. It was already ERROR while compensating
     * (which blocks the normal flow and, for a child, has already notified its parent as a
     * failure); this only records the clean-rollback terminal state, which is sticky so
     * nothing reverts it to ERROR.
     */
    private void markCompensated(Process process) {
        if (ProcessStatus.COMPENSATED.equals(process.getStatus())) {
            return;
        }
        var compensated = process.withStatus(ProcessStatus.COMPENSATED);
        if (compensated.getFinished() == null) {
            compensated = compensated.withFinished(LocalDateTime.now());
        }
        processRepository.save(compensated);
    }
}
