package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowDefinitionCrudAdapter;
import io.mateu.workflow.domain.WorkflowDefinition;
import io.mateu.workflow.infra.out.persistence.shared.DBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class WorkflowDefinitionDBCrudAdapter extends DBCrudAdapter<WorkflowDefinition, String> implements WorkflowDefinitionCrudAdapter {

    public WorkflowDefinitionDBCrudAdapter(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return WorkflowDefinition.class;
    }
}
