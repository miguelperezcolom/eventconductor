package io.mateu.workflow.application.usecases.workingcopy;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PromoteWorkingCopyUseCase {

    final WorkflowDefinitionRepository repository;

    public String handle(String workingCopyId) {
        var draft = repository.findById(workingCopyId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + workingCopyId));

        if (draft.status() != WorkflowDefinitionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only a DRAFT workflow can be promoted (was " + draft.status() + ")");
        }

        // A standalone draft (created new, never promoted before) has no original to replace:
        // promoting it simply activates it in place.
        if (draft.draftOfId() == null) {
            repository.save(draft.withStatus(WorkflowDefinitionStatus.ACTIVE));
            return draft.id();
        }

        var original = repository.findById(draft.draftOfId())
                .orElseThrow(() -> new IllegalStateException("Original workflow definition not found: " + draft.draftOfId()));

        var promoted = new WorkflowDefinition(
                original.id(),
                draft.name().endsWith(" [draft]")
                        ? draft.name().substring(0, draft.name().length() - " [draft]".length())
                        : draft.name(),
                original.version() + 1,
                draft.description(),
                original.status(),
                null,
                draft.limitConcurrentExecutions(),
                draft.maxConcurrentExecutions(),
                draft.enqueueOnLimit(),
                draft.cronExpression(),
                draft.defaultMaxStepExecutions(),
                draft.steps()
        );

        repository.save(promoted);
        repository.deleteAllById(List.of(workingCopyId));

        return promoted.id();
    }
}
