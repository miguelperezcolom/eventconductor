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
import java.util.List;
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
        apply(process, stepExecutionRepository.findByProcess(process));
    }

    /**
     * Same recompute, but over an already-loaded step list. The terminal-event handler loads the
     * process's steps once and feeds them to this and the compensation pass, so a single event no
     * longer reloads the whole step set three times over — this use case does not mutate any step,
     * so the caller's snapshot is still current here.
     */
    public void handle(ProcessStepExecutionUpdateCommand command, List<StepExecution> executions) {
        var process = repository.findById(command.processId()).orElseThrow();
        apply(process, executions);
    }

    private void apply(Process process, List<StepExecution> executions) {
        var previousStatus = process.getStatus();
        // One pass over the steps for everything the status derives from: how many completed,
        // whether any is still live, whether any has finally failed.
        long completed = 0;
        boolean anyLive = false;
        boolean anyFailed = false;
        for (StepExecution execution : executions) {
            switch (execution.getStatus()) {
                case COMPLETED -> completed++;
                case PENDING, RUNNING -> anyLive = true;
                // A step in a final failure state (retries already exhausted — auto-retry resets
                // the step to CREATED before this use case runs) means the process itself failed.
                case ERROR, TIMEOUT -> anyFailed = true;
                default -> { /* CREATED / CANCELLED do not move the process status */ }
            }
        }
        var status = executions.isEmpty() ? ProcessStatus.PENDING : ProcessStatus.COMPLETED;
        if (anyLive) {
            status = ProcessStatus.RUNNING;
        }
        var percent = executions.isEmpty() ? 0
                : Math.round(100d * completed / (double) executions.size());
        if (percent > 0) {
            status = ProcessStatus.RUNNING;
        }
        if (percent == 100) {
            status = ProcessStatus.COMPLETED;
        }
        if (anyFailed) {
            status = ProcessStatus.ERROR;
        }
        if (process.getStatus() != status) {
            if (process.getStatus() == ProcessStatus.CANCELLED
                    || process.getStatus() == ProcessStatus.ERROR
                    // COMPENSATED is a terminal saga-rollback state: once reached it must not
                    // fall back to ERROR (its failed step is still ERROR) or any other status.
                    || process.getStatus() == ProcessStatus.COMPENSATED
                    // PAUSED is sticky too: steps completing during the pause (worker reports,
                    // correlated messages) must not resurrect the process to RUNNING — only
                    // ResumeProcessUseCase leaves PAUSED.
                    || process.getStatus() == ProcessStatus.PAUSED) {
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
