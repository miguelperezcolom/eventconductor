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
    final CancelChildProcessService cancelChildProcessService;
    final DownstreamEventPublisher downstreamEventPublisher;
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;

    public void handle(StepOverProcessCommand command) {
        // Serialize per process: two concurrent step-overs (e.g. two parallel steps
        // completing at once, or two pods handling events for the same process) would
        // both see the next step as CREATED and dispatch it twice.
        // Named, because this is where a process actually moves and it is the span an operator
        // reading a trace is looking for — everything else in the picture is a database call.
        if (!processLockService.runExclusively(command.processId(),
                () -> workflowTracing.span("eventconductor.step-over",
                        java.util.Map.of("processId", command.processId()),
                        () -> doHandle(command)))) {
            log.error("Could not acquire lock for process {}, skipping step-over (another node is working on it)",
                    command.processId());
        }
    }

    private void doHandle(StepOverProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        var stepExecutions = stepExecutionRepository.findByProcess(process);

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
                downstreamEventPublisher.publish(new TaskCancellationRequested(stepExecution.getId()));
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
        }
    }

}
