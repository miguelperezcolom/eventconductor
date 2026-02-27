package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.infra.out.persistence.shared.DBRepository;
import io.mateu.workflow.infra.out.persistence.shared.GenericEntityRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class FormDBRepository extends DBRepository<Form, String> implements FormRepository {

    public FormDBRepository(GenericEntityRepository repository, StreamBridge streamBridge) {
        super(repository, streamBridge);
    }

    @Override
    public Class<?> entityClass() {
        return Form.class;
    }
}
