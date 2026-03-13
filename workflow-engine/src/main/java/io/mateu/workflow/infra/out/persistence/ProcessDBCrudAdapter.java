package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessCrudAdapter;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.infra.out.persistence.shared.DBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class ProcessDBCrudAdapter extends DBCrudAdapter<Process, String> implements ProcessCrudAdapter {

    public ProcessDBCrudAdapter(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return Process.class;
    }
}
