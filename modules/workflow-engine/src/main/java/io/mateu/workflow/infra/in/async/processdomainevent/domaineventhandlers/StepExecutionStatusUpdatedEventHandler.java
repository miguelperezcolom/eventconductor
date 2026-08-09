package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.BackoffPolicy;
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
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class StepExecutionStatusUpdatedEventHandler implements DomainEventHandler<StepExecutionStatusChanged> {

    final ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final DownstreamEventPublisher downstreamEventPublisher;
    final WorkflowMetrics workflowMetrics;
    final CancelChildProcessService cancelChildProcessService;
    final CompensationService compensationService;
    final BackoffPolicy backoffPolicy;

    /** What a terminal process still holds that can never run now. */
    private static final java.util.Set<StepExecutionStatus> CANCELLABLE_AT_THE_END = java.util.Set.of(
            StepExecutionStatus.CREATED,
            StepExecutionStatus.PENDING,
            StepExecutionStatus.RUNNING,
            StepExecutionStatus.AWAITING_RETRY);

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

            // Auto-retry: attempts remaining → park the step in AWAITING_RETRY for a backoff delay.
            // The timeout scheduler wakes it and CheckRetryUseCase re-dispatches it once the delay
            // has elapsed. Deliberately NOT re-dispatched here: an immediate step-over would burn
            // the whole retry budget in milliseconds against a worker that fails fast (bad config,
            // downstream 500) — a hot loop that hammers the failing dependency and defeats retries,
            // whose point is to wait out a transient fault.
            if (stepExecution.getAttemptCount() < step.retries()) {
                var backoff = backoffPolicy.nextDelay(stepExecution.getAttemptCount() + 1);
                stepExecution.scheduleRetry(backoff);
                stepExecutionRepository.save(stepExecution);
                workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                        WorkflowMetrics.RetryTrigger.AUTO);
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
     * compensations of every executed compensable step run sequentially in reverse execution
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
            case DONE -> markCompensated(process, executions);
            // A compensation step itself failed (its own retries exhausted): the rollback cannot go
            // on. This must NOT fall into the do-nothing bucket — that left the process ERROR and
            // half-rolled-back with no terminal state, no metric and no alert, which for a saga is
            // worse than not rolling back at all. Record the distinct COMPENSATION_FAILED terminal.
            case FAILED -> markCompensationFailed(process, executions);
            case NONE, WAITING -> { /* nothing to start now */ }
        }
    }

    /**
     * Marks a fully rolled-back process COMPENSATED, and finishes it properly.
     *
     * <p>It was already ERROR while compensating (which blocks the normal flow and, for a child,
     * has already notified its parent as a failure); this records the clean-rollback terminal
     * state, which is sticky so nothing reverts it to ERROR.
     *
     * <p>Finishing it properly means the two things the normal completion path does and this one
     * did not, because the process stopped mid-flow rather than reaching an END:
     *
     * <ul>
     *   <li><b>The steps that never ran are cancelled.</b> They were left CREATED — indefinitely,
     *       on a process that is over — so the UI showed a finished saga still holding steps that
     *       looked like they were waiting for their turn.</li>
     *   <li><b>The completion percentage is 100.</b> The rollback ran to the end; the process is
     *       as finished as one that completed, and a bar frozen at 43% says the opposite.</li>
     * </ul>
     */
    private void markCompensated(Process process, List<StepExecution> executions) {
        if (ProcessStatus.COMPENSATED.equals(process.getStatus())) {
            return;
        }
        cancelStepsThatNeverRan(executions);
        var compensated = process.withStatus(ProcessStatus.COMPENSATED).withCompletionPercentage(100);
        if (compensated.getFinished() == null) {
            compensated = compensated.withFinished(LocalDateTime.now());
        }
        processRepository.save(compensated);
    }

    /**
     * Records a halted saga rollback: a compensation step failed after its own retries, so the
     * process is <b>partially rolled back</b> and cannot finish undoing itself. Sets the distinct,
     * sticky {@link ProcessStatus#COMPENSATION_FAILED} terminal state — never leaving it in ERROR,
     * where a half-rolled-back saga would be indistinguishable from a plain failure and no metric
     * would fire.
     *
     * <p>Unlike {@link #markCompensated}, the completion percentage is left as it stands: the
     * rollback did NOT run to the end, and 100% would claim otherwise. The steps that never ran are
     * still cancelled — nothing can advance on a terminal process — and the failure is both metered
     * (the one compensation metric to alert on) and logged loudly. The parent of a child process
     * was already notified as a failure when this process first went ERROR, so no re-notification
     * is needed here.
     */
    private void markCompensationFailed(Process process, List<StepExecution> executions) {
        if (ProcessStatus.COMPENSATION_FAILED.equals(process.getStatus())) {
            return;
        }
        cancelStepsThatNeverRan(executions);
        var failed = process.withStatus(ProcessStatus.COMPENSATION_FAILED);
        if (failed.getFinished() == null) {
            failed = failed.withFinished(LocalDateTime.now());
        }
        processRepository.save(failed);
        workflowMetrics.compensationFailed(process.getWorkflowDefinitionId());
        log.warn("Saga rollback halted for process {} (definition {}): a compensation step failed after "
                        + "its retries, leaving the process partially rolled back. It is now "
                        + "COMPENSATION_FAILED and needs manual resolution.",
                process.getId(), process.getWorkflowDefinitionId());
    }

    /**
     * Cancels whatever is still live once the rollback is done — the same set an END transition
     * cancels when the flow reaches one. Nothing here can run any more: the frontier is held by a
     * failed step and the process is terminal.
     *
     * <p>"Still live" is not only rows waiting their turn. On a parallel flow, one branch failing
     * leaves its siblings dispatched and running at their workers, and flipping their row to
     * CANCELLED tells the worker nothing: it goes on and books a reservation for a saga that is
     * already rolled back — the very thing the rollback just finished undoing. So the in-flight
     * ones are cancelled the way {@code CancelProcessUseCase} has always cancelled them, with a
     * {@link TaskCancellationRequested}. The two paths do the same thing to a step and had no
     * business behaving differently.
     *
     * <p>The event is a request, not a guarantee: it races with the work, so a worker's handler
     * still has to be idempotent, and a worker that ignores the event is no worse off than before.
     * A step that timed out was already cancelled where the timeout was handled, and TIMEOUT is
     * terminal, so it never reaches this loop twice.
     */
    private void cancelStepsThatNeverRan(List<StepExecution> executions) {
        for (var execution : executions) {
            if (CANCELLABLE_AT_THE_END.contains(execution.getStatus())) {
                if (execution.getStatus().isInFlightAtAWorker()) {
                    downstreamEventPublisher.publish(new TaskCancellationRequested(execution.getId()));
                }
                execution.updateStatus(StepExecutionStatus.CANCELLED);
                stepExecutionRepository.save(execution);
            }
        }
    }
}
