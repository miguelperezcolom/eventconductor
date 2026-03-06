package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.FormCrudAdapter;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.infra.out.persistence.shared.DBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class FormDBCrudAdapter extends DBCrudAdapter<Form, String> implements FormCrudAdapter {

    public FormDBCrudAdapter(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return Form.class;
    }
}
