package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Disables an active workflow definition, moving it from {@code ACTIVE} to {@code DISABLED}. */
@Service
@RequiredArgsConstructor
@Transactional
public class DisableWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.status() != WorkflowDefinitionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only an ACTIVE workflow can be disabled (was " + definition.status() + ")");
        }
        repository.save(definition.withStatus(WorkflowDefinitionStatus.DISABLED));
    }
}
