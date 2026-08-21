package io.mateu.workflow.application.usecases.process.cancel;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public void handle(CancelProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (ProcessStatus.COMPLETED.equals(process.getStatus())
                || ProcessStatus.CANCELLED.equals(process.getStatus())) {
            return;
        }

        // Mark the process CANCELLED first: the step-status events emitted below re-enter
        // the orchestration loop, which must see the process as cancelled and not
        // dispatch any still-CREATED step in the middle of the cancellation.
        var cancelledProcess = process.withStatus(ProcessStatus.CANCELLED);
        processRepository.save(cancelledProcess);
        workflowMetrics.processCancelled(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(process));

        var stepExecutions = stepExecutionRepository.findByProcess(process);
        for (var stepExecution : stepExecutions) {
            if (!StepExecutionStatus.ERROR.equals(stepExecution.getStatus()) && !StepExecutionStatus.COMPLETED.equals(stepExecution.getStatus())
                    && !StepExecutionStatus.CANCELLED.equals(stepExecution.getStatus())) {
                if (stepExecution.getStatus().isInFlightAtAWorker()) {
                    downstreamEventPublisher.publish(new TaskCancellationRequested(stepExecution.getId()),
                            stepExecution.topic());
                }
                stepExecution.updateStatus(StepExecutionStatus.CANCELLED);
                stepExecutionRepository.save(stepExecution);
                // A cancelled PROCESS step must take its still-running child down with it
                // (which cascades into grandchildren through this very use case).
                cancelChildProcessService.stepReachedTerminalStatus(stepExecution);
            }
        }

        // If this process is a child workflow, the PROCESS step of the parent that spawned
        // it cannot succeed any more — error it.
        notifyParentStepService.processReachedTerminalStatus(cancelledProcess);
        // The same moment, seen the other way: this is where the process's whole run is
        // finally known, so it is where it can be written out as a trace.
        recordProcessTraceService.processReachedTerminalStatus(cancelledProcess);
    }
}
