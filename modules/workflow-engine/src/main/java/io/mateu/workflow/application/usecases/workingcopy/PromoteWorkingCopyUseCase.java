package io.mateu.workflow.application.usecases.workingcopy;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PromoteWorkingCopyUseCase {

    final WorkflowDefinitionRepository repository;

    public void handle(String workingCopyId) {
        var draft = repository.findById(workingCopyId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + workingCopyId));

        if (draft.draftOfId() == null) {
            throw new IllegalStateException("Workflow '" + draft.name() + "' is not a working copy");
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
                draft.steps()
        );

        repository.save(promoted);
        repository.deleteAllById(List.of(workingCopyId));
    }
}
