package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.WorkflowOrchestrationService;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StepOverProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessLockService processLockService;
    final WorkflowMetrics workflowMetrics;
    final WorkflowOrchestrationService workflowOrchestrationService;
    final NotifyParentStepService notifyParentStepService;
    final io.mateu.workflow.application.services.RecordProcessTraceService recordProcessTraceService;
    final CancelChildProcessService cancelChildProcessService;
    final DownstreamEventPublisher downstreamEventPublisher;
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final io.mateu.workflow.application.services.ProcessTrace processTrace;

    public void handle(StepOverProcessCommand command) {
        // Serialize per process: two concurrent step-overs (e.g. two parallel steps
        // completing at once, or two pods handling events for the same process) would
        // both see the next step as CREATED and dispatch it twice.
        // Named, because this is where a process actually moves and it is the span an operator
        // reading a trace is looking for — everything else in the picture is a database call.
        // Anchored to the process's own trace rather than started as a root of its own. Without
        // this, each step-over is the beginning of a fresh trace — a pile of two-millisecond spans
        // with nothing to say which process they belong to or what came before them — because the
        // context that produced the event does not survive the outbox row and the broker record in
        // between. The anchor is derived from the process id, so every pod computes the same one.
        if (!processLockService.runExclusively(command.processId(),
                () -> workflowTracing.continuing(
                        processTrace.anchorFor(command.processId()),
                        "eventconductor.step-over",
                        java.util.Map.of("eventconductor.process.id", command.processId()),
                        () -> doHandle(command)))) {
            log.error("Could not acquire lock for process {}, skipping step-over (another node is working on it)",
                    command.processId());
        }
    }

    private void doHandle(StepOverProcessCommand command) {
        // Steps first, process second, and the order is load-bearing. This query auto-flushes
        // whatever writes are pending on JPA — including a version bump on this very process, left
        // by a caller that saved it just before (ResumeProcessUseCase does exactly that). Mapping
        // the process first would capture the version that flush is about to supersede, and the
        // save below would then fail optimistic locking and roll the whole handler back.
        var stepExecutions = stepExecutionRepository.findByProcessId(command.processId());
        var process = processRepository.findById(command.processId()).orElseThrow();

        var result = workflowOrchestrationService.calculateNextTransitions(process, stepExecutions);

        if (result.getUpdatedProcess() != process) {
            processRepository.save(result.getUpdatedProcess());
        }

        // Status as it was before the transition decided anything. The orchestration service is
        // pure and hands back copies (@With), so the list read above still holds the old values —
        // which is the only way to tell a step that was merely waiting its turn from one a worker
        // is running right now.
        var statusBefore = stepExecutions.stream()
                .collect(java.util.stream.Collectors.toMap(StepExecution::getId, StepExecution::getStatus));

        result.getStepsToSave().forEach(stepExecution -> {
            stepExecutionRepository.save(stepExecution);
            // A branch still running at a worker when another branch reaches END is cancelled here
            // by a plain status flip, which reaches nobody: the worker finishes and reports on a
            // process that is already over. Same reasoning — and same event — as the saga rollback
            // path and CancelProcessUseCase.
            var before = statusBefore.get(stepExecution.getId());
            if (StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus())
                    && before != null && before.isInFlightAtAWorker()) {
                downstreamEventPublisher.publish(new TaskCancellationRequested(stepExecution.getId()),
                        stepExecution.topic());
            }
            // END-transition and implicit-completion cancellations (and start-time errors)
            // flow through here — a PROCESS step ending CANCELLED/ERROR must take its
            // still-running child down with it.
            cancelChildProcessService.stepReachedTerminalStatus(stepExecution);
        });

        if (result.isProcessErrored()) {
            workflowMetrics.processErrored(
                    result.getUpdatedProcess().getWorkflowDefinitionId(),
                    WorkflowMetrics.durationOf(result.getUpdatedProcess())
            );
        } else if (result.isProcessCompleted()) {
            workflowMetrics.processCompleted(
                    result.getUpdatedProcess().getWorkflowDefinitionId(),
                    WorkflowMetrics.durationOf(result.getUpdatedProcess())
            );
        }

        // If this process is a child workflow and just reached a terminal status, complete
        // (or error) the PROCESS step of the parent that spawned it.
        if (result.isProcessErrored() || result.isProcessCompleted()) {
            notifyParentStepService.processReachedTerminalStatus(result.getUpdatedProcess());
            // The same moment, seen the other way: this is where the process's whole run is
            // finally known, so it is where it can be written out as a trace.
            recordProcessTraceService.processReachedTerminalStatus(result.getUpdatedProcess());
        }
    }

}
