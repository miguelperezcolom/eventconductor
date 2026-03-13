package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.application.out.WorkflowDefinitionCrudAdapter;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitionRow;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitionView;
import io.mateu.workflow.infra.out.persistence.shared.DBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WorkflowDefinitionDBCrudAdapter
        extends DBCrudAdapter<WorkflowDefinition, String>
        implements WorkflowDefinitionCrudAdapter {

    public WorkflowDefinitionDBCrudAdapter(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return WorkflowDefinition.class;
    }

    @Override
    public Optional findById(String id) {
        return Optional.empty();
    }

    @Override
    public ListingData<WorkflowDefinitionRow> search(String searchText, NoFilters noFilters, Pageable pageable) {
        return null;
    }

    @Override
    public WorkflowDefinitionView getView(WorkflowDefinition entity) {
        return null;
    }

    @Override
    public WorkflowDefinitionView getEditor(WorkflowDefinition entity) {
        return null;
    }

    @Override
    public WorkflowDefinitionView getCreationForm() {
        return null;
    }
}
