package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Re-enables a disabled workflow definition so it accepts new instances again. */
@Service
@RequiredArgsConstructor
@Transactional
public class EnableWorkflowDefinitionUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow definition not found: " + workflowDefinitionId));
        if (definition.declaredStatus() != WorkflowStatus.ACTIVE) {
            // The declaration is a floor. Refusing out loud rather than clearing the runtime
            // status and leaving the workflow just as disabled, which would look like the button
            // is broken: the answer is in the file, and that is where it has to be changed.
            throw new IllegalStateException("Workflow definition '" + definition.name() + "' is "
                    + definition.declaredStatus() + " in its own definition, so it cannot be"
                    + " enabled here. Change it in the definition file and import it again.");
        }
        if (definition.runtimeStatus() != WorkflowStatus.ACTIVE) {
            repository.save(definition.withRuntimeStatus(WorkflowStatus.ACTIVE));
        }
    }
}
