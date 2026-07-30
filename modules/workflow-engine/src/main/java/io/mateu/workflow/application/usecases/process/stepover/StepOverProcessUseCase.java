package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessLocks;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.services.WorkflowOrchestrationService;
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

    public void handle(StepOverProcessCommand command) {
        // Serialize per process: two concurrent step-overs (e.g. two parallel steps
        // completing at once, or two pods handling events for the same process) would
        // both see the next step as CREATED and dispatch it twice.
        if (!ProcessLocks.lockWithRetry(processLockService, command.processId())) {
            log.error("Could not acquire lock for process {}, skipping step-over (another node is working on it)",
                    command.processId());
            return;
        }
        try {
            doHandle(command);
        } finally {
            processLockService.unlock(command.processId());
        }
    }

    private void doHandle(StepOverProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        var stepExecutions = stepExecutionRepository.findByProcess(process);

        var result = workflowOrchestrationService.calculateNextTransitions(process, stepExecutions);

        if (result.getUpdatedProcess() != process) {
            processRepository.save(result.getUpdatedProcess());
        }

        result.getStepsToSave().forEach(stepExecutionRepository::save);

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
