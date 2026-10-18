package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pauses a workflow definition: sets its runtime {@code paused} flag and pauses every
 * PENDING/RUNNING process of the definition. While paused, new instances are still
 * accepted (cron included) but are created already PAUSED ({@code CreateProcessUseCase}),
 * so a later {@code ResumeWorkflowUseCase} picks them all up. Orthogonal to the
 * DRAFT/ACTIVE/... lifecycle status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PauseWorkflowUseCase {

    final WorkflowDefinitionRepository repository;
    final ProcessRepository processRepository;
    final PauseProcessUseCase pauseProcessUseCase;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (!definition.paused()) {
            repository.save(definition.withPaused(true));
        }
        processRepository.findAll().stream()
                .filter(process -> workflowDefinitionId.equals(process.getWorkflowDefinitionId()))
                .filter(process -> ProcessStatus.PENDING.equals(process.getStatus())
                        || ProcessStatus.RUNNING.equals(process.getStatus()))
                .forEach(process -> {
                    // Each process on its own, as ResumeWorkflowUseCase does: one that cannot be
                    // paused right now (its lock is busy) must not leave every process after it
                    // running.
                    try {
                        pauseProcessUseCase.handle(new PauseProcessCommand(process.getId()));
                    } catch (RuntimeException e) {
                        log.error("Could not pause process {} of definition {}; it keeps running",
                                process.getId(), workflowDefinitionId, e);
                    }
                });
    }
}
