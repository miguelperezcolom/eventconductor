package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enables a disabled workflow definition, moving it from {@code DISABLED} back to {@code ACTIVE}. */
@Service
@RequiredArgsConstructor
@Transactional
public class EnableWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.status() != WorkflowDefinitionStatus.DISABLED) {
            throw new IllegalStateException(
                    "Only a DISABLED workflow can be enabled (was " + definition.status() + ")");
        }
        repository.save(definition.withStatus(WorkflowDefinitionStatus.ACTIVE));
    }
}
