package io.mateu.workflow.application.usecases.process.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
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
    final io.mateu.workflow.application.services.RecordProcessTraceService recordProcessTraceService;

    public void handle(ProcessStepExecutionUpdateCommand command) {
        // Steps first, process second — see StepOverProcessUseCase for why the order matters.
        // This is the call site the failure surfaced at: StepOverProcessUseCase runs immediately
        // before it during a resume, so there is always a pending write here to flush.
        var executions = stepExecutionRepository.findByProcessId(command.processId());
        var process = repository.findById(command.processId()).orElseThrow();
        apply(process, executions);
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

    private void apply(Process process, List<StepExecution> allExecutions) {
        // The current execution of each step: a loop's earlier passes are history and a forEach's
        // iterations answer to their container — counting them would skew both the percentage and
        // the status (a pass that was re-run is not a failure; a container decides for its items).
        var executions = io.mateu.workflow.domain.services.StepExecutions.current(allExecutions);
        var previousStatus = process.getStatus();
        // One pass over the steps for everything the status derives from: how many completed,
        // whether any is still live, whether any has finally failed.
        long completed = 0;
        boolean anyLive = false;
        boolean anyFailed = false;
        for (StepExecution execution : executions) {
            switch (execution.getStatus()) {
                case COMPLETED -> completed++;
                // AWAITING_RETRY is a step waiting out its backoff before running again: the process
                // is still in flight, not finished — without this it falls through to the default and
                // a lone retrying step would read as a completed process.
                // WAITING_ON_LOCK is a step queued behind a lock holder: it will run once the lock
                // frees, so it keeps the process in flight just the same.
                case PENDING, RUNNING, AWAITING_RETRY, WAITING_ON_LOCK -> anyLive = true;
                // A step in a final failure state (retries already exhausted — a step with retries
                // left is parked in AWAITING_RETRY, counted as live above) means the process failed.
                case ERROR -> anyFailed = true;
                case TIMEOUT -> {
                    // A timeout the step routes to an on-timeout step is handled forward (flow moves
                    // to that step), not a process failure.
                    var step = pojoFromJson(execution.getStepJson(), Step.class);
                    if (step.onTimeoutStepId() == null || step.onTimeoutStepId().isBlank()) {
                        anyFailed = true;
                    }
                }
                default -> { /* CREATED / CANCELLED do not move the process status */ }
            }
        }
        // Floored, not rounded: rounding turned 199 of 200 steps into 100%, and 100% alone used
        // to mean COMPLETED — a process with a step still RUNNING reported as finished.
        var percent = executions.isEmpty() ? 0 : (100L * completed) / executions.size();
        // COMPLETED only when every step completed and nothing is live. A finished flow whose
        // untaken branches were CANCELLED reaches COMPLETED through the END/implicit-completion
        // transition in step-over, not through this count; anything short of that is in flight.
        var status = executions.isEmpty() ? ProcessStatus.PENDING
                : !anyLive && completed == executions.size() ? ProcessStatus.COMPLETED
                : anyLive || completed > 0 ? ProcessStatus.RUNNING
                // Nothing completed, live or failed (all CREATED/CANCELLED): the steps say nothing
                // about where the process is, so it stays where it is. This used to fall through to
                // COMPLETED, which now that COMPLETED is sticky would finish a process for good.
                : java.util.Objects.requireNonNullElse(process.getStatus(), ProcessStatus.PENDING);
        if (anyFailed) {
            status = ProcessStatus.ERROR;
        }
        if (process.getStatus() != status) {
            if (process.getStatus() == ProcessStatus.CANCELLED
                    || process.getStatus() == ProcessStatus.ERROR
                    // COMPLETED is terminal too: a late or redelivered status event, recomputed
                    // over steps an END transition left CANCELLED, must not reopen a finished
                    // process as RUNNING (and notify a parent, or count it, twice).
                    || process.getStatus() == ProcessStatus.COMPLETED
                    // COMPENSATED is a terminal saga-rollback state: once reached it must not
                    // fall back to ERROR (its failed step is still ERROR) or any other status.
                    || process.getStatus() == ProcessStatus.COMPENSATED
                    // COMPENSATION_FAILED is likewise terminal and sticky: a halted rollback must
                    // not be resurrected or masked back into a plain ERROR.
                    || process.getStatus() == ProcessStatus.COMPENSATION_FAILED
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
                // The same moment, seen the other way: this is where the process's whole run is
                // finally known, so it is where it can be written out as a trace.
                recordProcessTraceService.processReachedTerminalStatus(process);
            }
        }
    }

}
