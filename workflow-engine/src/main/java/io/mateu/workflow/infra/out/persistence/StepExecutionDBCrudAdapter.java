package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.StepExecutionCrudAdapter;
import io.mateu.workflow.domain.StepExecution;
import io.mateu.workflow.infra.out.persistence.shared.CompositionDBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class StepExecutionDBCrudAdapter extends CompositionDBCrudAdapter<StepExecution, String, String> implements StepExecutionCrudAdapter {

    public StepExecutionDBCrudAdapter(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return StepExecution.class;
    }

    @Override
    public boolean belongsToParent(StepExecution entity, String parentId) {
        return entity.processId().equals(parentId);
    }
}
