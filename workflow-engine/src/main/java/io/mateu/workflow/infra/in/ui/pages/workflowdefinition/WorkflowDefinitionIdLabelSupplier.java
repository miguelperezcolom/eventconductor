package io.mateu.workflow.infra.in.ui.pages.workflowdefinition;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.application.out.WorkflowDefinitionCrudAdapter;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowDefinitionIdLabelSupplier implements LabelSupplier {

    final WorkflowDefinitionCrudAdapter countryRepository;

    @Override
    public String label(Object id, HttpRequest httpRequest) {
        return countryRepository.findById((String) id)
                .map(WorkflowDefinition::name)
                .orElse("No workflow definition with id " + id);
    }
}
