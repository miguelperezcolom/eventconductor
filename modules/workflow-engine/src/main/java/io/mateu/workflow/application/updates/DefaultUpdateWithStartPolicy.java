package io.mateu.workflow.application.updates;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;

/**
 * Today's business-key rule: a key names one process, for ever (the column is unique). A process with the
 * key of the same definition is the target, whatever its status — a finished one then answers
 * {@code 409 NOT_ACCEPTING} through the ordinary path; a process of another definition is a conflict;
 * no process means start one. Used unless the application declares an {@link UpdateWithStartPolicy} bean.
 */
@RequiredArgsConstructor
public class DefaultUpdateWithStartPolicy implements UpdateWithStartPolicy {

    final ProcessRepository processRepository;

    @Override
    public Resolution resolve(WorkflowDefinition definition, String businessKey) {
        var existing = processRepository.findByBusinessKey(businessKey).orElse(null);
        if (existing == null) {
            return new Start();
        }
        if (!definition.id().equals(existing.getWorkflowDefinitionId())) {
            return new Conflict("Business key '" + businessKey + "' belongs to a process of '"
                    + existing.getWorkflowDefinitionId() + "', not of '" + definition.id() + "'.");
        }
        return new Existing(existing);
    }
}
