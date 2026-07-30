package io.mateu.workflow.application.usecases.process.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProcessUpdateStepExecutionUpdateUseCase {

    final ProcessRepository repository;
    final StepExecutionRepository stepExecutionRepository;
    final WorkflowMetrics workflowMetrics;
    final NotifyParentStepService notifyParentStepService;

    public void handle(ProcessStepExecutionUpdateCommand command) {
        var process = repository.findById(command.processId()).orElseThrow();
        var previousStatus = process.getStatus();
        var executions = stepExecutionRepository.findByProcess(process);
        var status = !executions.isEmpty() ? ProcessStatus.COMPLETED:ProcessStatus.PENDING;
        for (StepExecution execution : executions) {
            if (StepExecutionStatus.PENDING == execution.getStatus()) {
                status = ProcessStatus.RUNNING;
            }
        }
        for (StepExecution execution : executions) {
            if (StepExecutionStatus.RUNNING == execution.getStatus()) {
                status = ProcessStatus.RUNNING;
            }
        }
        var percent = executions.isEmpty() ? 0
                : Math.round(100d * executions.stream().filter(e -> StepExecutionStatus.COMPLETED.equals(e.getStatus())).count() / (double) executions.size());
        if (percent > 0) {
            status = ProcessStatus.RUNNING;
        }
        if (percent == 100) {
            status = ProcessStatus.COMPLETED;
        }
        // A step in a final failure state (retries already exhausted — auto-retry resets the
        // step to CREATED before this use case runs) means the process itself has failed.
        var anyFailed = executions.stream().anyMatch(e ->
                StepExecutionStatus.ERROR.equals(e.getStatus())
                        || StepExecutionStatus.TIMEOUT.equals(e.getStatus()));
        if (anyFailed) {
            status = ProcessStatus.ERROR;
        }
        if (process.getStatus() != status) {
            if (process.getStatus() == ProcessStatus.CANCELLED
                    || process.getStatus() == ProcessStatus.ERROR) {
                status = process.getStatus();
                percent = process.getCompletionPercentage();
            }
        }
        process = process.withStatus(status).withCompletionPercentage((int) percent);
        if (process.getStarted() == null && (status.equals(ProcessStatus.RUNNING)
                || status.equals(ProcessStatus.COMPLETED)
                || status.equals(ProcessStatus.CANCELLED)
                || status.equals(ProcessStatus.ERROR))) {
            process = process.withStarted(LocalDateTime.now());
        }
        if (process.getFinished() == null && (status.equals(ProcessStatus.COMPLETED)
                || status.equals(ProcessStatus.CANCELLED)
                || status.equals(ProcessStatus.ERROR))) {
            process = process.withFinished(LocalDateTime.now());
        }
        repository.save(process);

        // Only the actual transition counts — re-evaluations where the status is
        // unchanged (or sticky ERROR/CANCELLED) must not inflate the counters.
        if (previousStatus != process.getStatus()) {
            if (process.getStatus() == ProcessStatus.COMPLETED) {
                workflowMetrics.processCompleted(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(process));
            } else if (process.getStatus() == ProcessStatus.ERROR) {
                workflowMetrics.processErrored(process.getWorkflowDefinitionId(), WorkflowMetrics.durationOf(process));
            }
            // If this process is a child workflow and just reached a terminal status,
            // complete (or error) the PROCESS step of the parent that spawned it.
            if (process.getStatus() == ProcessStatus.COMPLETED
                    || process.getStatus() == ProcessStatus.ERROR
                    || process.getStatus() == ProcessStatus.CANCELLED) {
                notifyParentStepService.processReachedTerminalStatus(process);
            }
        }
    }

}
