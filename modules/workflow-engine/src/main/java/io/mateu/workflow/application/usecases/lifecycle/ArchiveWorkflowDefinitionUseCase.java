package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archives a workflow definition by moving it to the terminal {@code ARCHIVED} status.
 * An {@code ACTIVE} workflow must be disabled first — it cannot be archived directly.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ArchiveWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.status() == WorkflowDefinitionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Workflow '" + definition.name() + "' is already archived");
        }
        if (definition.status() == WorkflowDefinitionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "An ACTIVE workflow must be disabled before it can be archived");
        }
        repository.save(definition.withStatus(WorkflowDefinitionStatus.ARCHIVED));
    }
}
