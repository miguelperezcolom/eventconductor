package io.mateu.workflow.application.usecases.process.cancel;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class CancelProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final DownstreamEventPublisher downstreamEventPublisher;
    final WorkflowMetrics workflowMetrics;
    final NotifyParentStepService notifyParentStepService;
    final io.mateu.workflow.application.services.RecordProcessTraceService recordProcessTraceService;
    final CancelChildProcessService cancelChildProcessService;
    final ProcessLockService processLockService;
    // A provider: step-over reaches this use case back through the child-cancellation cascade.
    final org.springframework.beans.factory.ObjectProvider<StepOverProcessUseCase> stepOverProcessUseCase;

    /** What the locked part decided, for the cascade that runs after the lock is released. */
    private record Cancellation(Process cancelledProcess, List<StepExecution> cancelledSteps, boolean repeated) {
    }

    /**
     * Whether a process in {@code status} is cancelled by a request: anything but COMPLETED. A process
     * already CANCELLED is accepted, because the request re-runs its (idempotent) cascade. Shared with
     * the REST API, which refuses the rest synchronously.
     */
    public static boolean acceptsFrom(ProcessStatus status) {
        return !ProcessStatus.COMPLETED.equals(status);
    }

    public void handle(CancelProcessCommand command) {
        // This process's own read-check-write runs under its lock, like every other writer of it:
        // the cancel can no longer interleave with the relay moving the process (a step-over
        // dispatching a step the cancel just skipped, a stale version overwriting CANCELLED). A
        // lock that cannot be had fails rather than doing nothing silently.
        var outcome = new AtomicReference<Cancellation>();
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(),
                () -> outcome.set(cancelUnderLock(command)));
        var cancellation = outcome.get();
        if (cancellation == null) {
            return;
        }
        // The cascade reaches OTHER processes — children through this very use case, the parent
        // through its step update — and each of those takes its own lock. Running it after this
        // one is released keeps a parent cancel and a child reporting back to its parent from
        // holding one lock each while waiting for the other's.
        for (var stepExecution : cancellation.cancelledSteps()) {
            // A cancelled PROCESS step must take its still-running child down with it
            // (which cascades into grandchildren through this very use case).
            cancelChildProcessService.stepReachedTerminalStatus(stepExecution);
        }
        var cancelledProcess = cancellation.cancelledProcess();
        if (cancellation.repeated()) {
            // The trace was written (or lost) with the first cancel: it is a transition, not a state.
            notifyParentStepService.processReachedTerminalStatus(cancelledProcess);
            return;
        }
        // If this process is a child workflow, the PROCESS step of the parent that spawned
        // it cannot succeed any more — error it.
        notifyParentStepService.processReachedTerminalStatus(cancelledProcess);
        // The same moment, seen the other way: this is where the process's whole run is
        // finally known, so it is where it can be written out as a trace.
        recordProcessTraceService.processReachedTerminalStatus(cancelledProcess);
    }

    /**
     * Null when there is nothing to cancel: the process already finished. A process already
     * CANCELLED is not nothing: its cascade runs after its own commit, outside the lock, so a pod
     * that died in between left the children running and the parent step waiting — and the cancel
     * that is redelivered for it (or sent again by an operator) finds the process CANCELLED. The
     * cascade is idempotent (only children still running are cancelled, only a parent step still
     * waiting is failed), so it is simply run again.
     */
    private Cancellation cancelUnderLock(CancelProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (!acceptsFrom(process.getStatus())) {
            return null;
        }
        if (ProcessStatus.CANCELLED.equals(process.getStatus())) {
            var cancelledSteps = stepExecutionRepository.findByProcess(process).stream()
                    .filter(stepExecution -> StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus()))
                    .toList();
            return new Cancellation(process, cancelledSteps, true);
        }

        // Mark the process CANCELLED first: the step-status events emitted below re-enter
        // the orchestration loop, which must see the process as cancelled and not
        // dispatch any still-CREATED step in the middle of the cancellation.
        var cancelledProcess = process.withStatus(ProcessStatus.CANCELLED);
        // A cancelled process has ended: stamp when, as every other terminal transition does, so the
        // retention purge (counted from the end) and the duration metrics have something to count from.
        if (cancelledProcess.getFinished() == null) {
            cancelledProcess = cancelledProcess.withFinished(java.time.LocalDateTime.now());
        }
        processRepository.save(cancelledProcess);
        workflowMetrics.processCancelled(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(process));

        var stepExecutions = stepExecutionRepository.findByProcess(process);
        var cancelledSteps = new ArrayList<StepExecution>();
        for (var stepExecution : stepExecutions) {
            if (!StepExecutionStatus.ERROR.equals(stepExecution.getStatus()) && !StepExecutionStatus.COMPLETED.equals(stepExecution.getStatus())
                    && !StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus())) {
                if (stepExecution.getStatus().isInFlightAtAWorker()) {
                    downstreamEventPublisher.publish(new TaskCancellationRequested(stepExecution.getId()),
                            stepExecution.topic());
                }
                stepExecution.updateStatus(StepExecutionStatus.CANCELLED);
                stepExecutionRepository.save(stepExecution);
                cancelledSteps.add(stepExecution);
            }
        }
        // A cancelled process holds no lock: free every one it holds (a LOCK step's, its definition's
        // processLock) and leave every queue it waits in, admitting the next waiter. Inside this
        // lock, with the cancel, so a wake that fails rolls the cancellation back with it and is
        // redelivered whole rather than leaving a granted waiter that nobody ever wakes.
        stepOverProcessUseCase.getObject().releaseLocksOf(process.getId());
        return new Cancellation(cancelledProcess, cancelledSteps, false);
    }
}
