package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reactivates an archived workflow definition, moving it from {@code ARCHIVED} back to {@code ACTIVE}. */
@Service
@RequiredArgsConstructor
@Transactional
public class ReactivateWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.status() != WorkflowDefinitionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Only an ARCHIVED workflow can be reactivated (was " + definition.status() + ")");
        }
        repository.save(definition.withStatus(WorkflowDefinitionStatus.ACTIVE));
    }
}
