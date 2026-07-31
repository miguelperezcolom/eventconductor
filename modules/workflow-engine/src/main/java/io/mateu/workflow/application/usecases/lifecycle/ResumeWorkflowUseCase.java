package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resumes a paused workflow definition: clears its runtime {@code paused} flag and resumes
 * every PAUSED process of the definition — the ones paused with it and the ones born
 * paused while it was (for those the clock shift is vacuous: nothing had started).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeWorkflowUseCase {

    final WorkflowDefinitionRepository repository;
    final ProcessRepository processRepository;
    final ResumeProcessUseCase resumeProcessUseCase;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.paused()) {
            repository.save(definition.withPaused(false));
        }
        processRepository.findAll().stream()
                .filter(process -> workflowDefinitionId.equals(process.getWorkflowDefinitionId()))
                .filter(process -> ProcessStatus.PAUSED.equals(process.getStatus()))
                .forEach(process -> resumeProcessUseCase.handle(new ResumeProcessCommand(process.getId())));
    }
}
