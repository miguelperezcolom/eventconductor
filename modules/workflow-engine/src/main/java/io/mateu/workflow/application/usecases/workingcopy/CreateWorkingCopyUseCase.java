package io.mateu.workflow.application.usecases.workingcopy;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CreateWorkingCopyUseCase {

    final WorkflowDefinitionRepository repository;

    public String handle(String workflowDefinitionId) {
        var original = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + workflowDefinitionId));

        boolean alreadyHasWorkingCopy = repository.findAll().stream()
                .anyMatch(d -> workflowDefinitionId.equals(d.draftOfId()));
        if (alreadyHasWorkingCopy) {
            throw new IllegalStateException("A working copy already exists for workflow '" + original.name() + "'");
        }

        var copy = new WorkflowDefinition(
                UUID.randomUUID().toString(),
                original.name() + " [draft]",
                original.version(),
                original.description(),
                WorkflowDefinitionStatus.DRAFT,
                workflowDefinitionId,
                original.limitConcurrentExecutions(),
                original.maxConcurrentExecutions(),
                original.enqueueOnLimit(),
                original.cronExpression(),
                original.defaultMaxStepExecutions(),
                original.steps()
        );
        return repository.save(copy);
    }
}
