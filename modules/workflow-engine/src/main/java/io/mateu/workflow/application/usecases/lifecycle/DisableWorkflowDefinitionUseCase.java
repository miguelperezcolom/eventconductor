package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Disables a workflow definition: while disabled it accepts no new instances (cron included). */
@Service
@RequiredArgsConstructor
@Transactional
public class DisableWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.runtimeStatus() == WorkflowStatus.ACTIVE) {
            repository.save(definition.withRuntimeStatus(WorkflowStatus.DISABLED));
        }
    }
}
