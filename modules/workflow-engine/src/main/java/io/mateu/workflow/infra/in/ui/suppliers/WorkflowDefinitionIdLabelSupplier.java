package io.mateu.workflow.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowDefinitionIdLabelSupplier implements LabelSupplier {

    final WorkflowDefinitionRepository workflowDefinitionRepository;

    @Override
    public String label(Object id, HttpRequest httpRequest) {
        return workflowDefinitionRepository.findById((String) id)
                .map(WorkflowDefinition::name)
                .orElse("No workflow definition with id " + id);
    }
}
