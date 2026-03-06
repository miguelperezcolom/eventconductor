package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.StepCrudAdapter;
import io.mateu.workflow.domain.Step;
import io.mateu.workflow.infra.out.persistence.shared.DBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class StepDBCrudAdapter extends DBCrudAdapter<Step, String> implements StepCrudAdapter {

    public StepDBCrudAdapter(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return Step.class;
    }
}
