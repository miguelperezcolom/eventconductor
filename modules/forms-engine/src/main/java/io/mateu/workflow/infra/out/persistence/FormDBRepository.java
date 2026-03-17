package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FormDBRepository implements FormRepository {

    final FormEntityRepository formEntityRepository;
    final FieldEntityRepository fieldEntityRepository;
    final StreamBridge streamBridge;

    @Override
    public Optional<Form> findById(String id) {
        return formEntityRepository.findById(id)
                .map(this::map);
    }

    private Form map(FormEntity formEntity) {
        return new Form(
                formEntity.getId(),
                formEntity.getName(),
                formEntity.getDescription(),
                fieldEntityRepository.findByFormId(formEntity.getId()).stream()
                        .map(fieldEntity -> new Field(
                                fieldEntity.getId(),
                                fieldEntity.getLabel(),
                                FieldDataType.valueOf(fieldEntity.getDataType()),
                                FieldStereotype.valueOf(fieldEntity.getStereotype()),
                                fieldEntity.isRequired(),
                                fieldEntity.getDescription()
                        )).toList());
    }

    @Override
    public String save(Form form) {
        if (form.fields() != null) {
            form.fields().stream().map(field -> new FieldEntity(
                field.id(),
                    form.id(),
                    field.label(),
                    field.dataType().name(),
                    field.stereotype().name(),
                    field.required(),
                    field.description()
            )).forEach(fieldEntityRepository::save);
        }
        formEntityRepository.save(new FormEntity(
                form.id(), form.name(), form.description()
        ));
        return form.id();
    }

    @Override
    public List<Form> findAll() {
        return formEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        formEntityRepository.deleteAllById(selectedIds);
    }
}
