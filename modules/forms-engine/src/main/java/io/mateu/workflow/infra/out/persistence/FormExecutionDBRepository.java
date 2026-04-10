package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class FormExecutionDBRepository implements FormExecutionRepository {

    final FormExecutionEntityRepository formExecutionEntityRepository;
    final StreamBridge streamBridge;

    @Override
    public Optional<FormExecution> findById(String id) {
        return formExecutionEntityRepository.findById(id)
                .map(this::map);
    }

    private FormExecution map(FormExecutionEntity entity) {
        return new FormExecution(
                entity.getId(),
                entity.getFormId(),
                entity.getProcessId(),
                entity.getStepId(),
                entity.getStepExecutionId(),
                FormExecutionStatus.valueOf(entity.getStatus()),
                entity.getUserId(),
                entity.getUserGroup(),
                listFromJson(entity.getVariables(), Variable.class),
                listFromJson(entity.getValues(), Value.class)
                );
    }

    @Override
    public String save(FormExecution execution) {
        formExecutionEntityRepository.save(new FormExecutionEntity(
                execution.id(),
                execution.formId(),
                execution.processId(),
                execution.stepId(),
                execution.stepExecutionId(),
                toJson(execution.variables()),
                toJson(execution.values()),
                execution.status().name(),
                execution.userId(),
                execution.userGroup()
        ));
        return execution.id();
    }

    @Override
    public List<FormExecution> findAll() {
        return formExecutionEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        formExecutionEntityRepository.deleteAllById(selectedIds);
    }
}
