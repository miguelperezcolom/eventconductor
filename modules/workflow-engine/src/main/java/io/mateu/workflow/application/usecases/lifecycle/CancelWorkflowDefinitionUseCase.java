package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cancels a workflow definition by moving it to the terminal {@code ARCHIVED} status. */
@Service
@RequiredArgsConstructor
@Transactional
public class CancelWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.status() == WorkflowDefinitionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Workflow '" + definition.name() + "' is already archived");
        }
        repository.save(definition.withStatus(WorkflowDefinitionStatus.ARCHIVED));
    }
}
