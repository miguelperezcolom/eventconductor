package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.services.FormValidator;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "forms.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class FormDBRepository implements FormRepository {

    /**
     * What {@code form-schema.json} documents as the default when a definition omits
     * {@code stereotype}. Jackson leaves the record component null, and every write path — the Crud
     * UI, git import, MCP — can produce one.
     */
    private static final FieldStereotype DEFAULT_STEREOTYPE = FieldStereotype.regular;

    final FormEntityRepository formEntityRepository;
    final FieldEntityRepository fieldEntityRepository;
    final FormValidator formValidator;

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
                fieldEntityRepository.findByFormIdOrderByFieldOrderAsc(formEntity.getId()).stream()
                        .map(fieldEntity -> new Field(
                                fieldEntity.getId(),
                                fieldEntity.getLabel(),
                                FieldDataType.valueOf(fieldEntity.getDataType()),
                                fieldEntity.getStereotype() == null
                                        ? DEFAULT_STEREOTYPE
                                        : FieldStereotype.valueOf(fieldEntity.getStereotype()),
                                fieldEntity.isRequired(),
                                fieldEntity.getDescription()
                        )).toList());
    }

    /**
     * Replaces the form's fields rather than merging them: a save carries the whole form, so a field
     * dropped from the definition has to disappear from the table too. Upserting alone left it
     * behind, and the next read handed the caller back a field the form no longer declares.
     */
    @Override
    @Transactional
    public String save(Form form) {
        formValidator.validate(form);
        fieldEntityRepository.deleteByFormId(form.id());
        var fields = form.fields() == null ? List.<Field>of() : form.fields();
        for (int order = 0; order < fields.size(); order++) {
            var field = fields.get(order);
            fieldEntityRepository.save(new FieldEntity(
                    form.id(),
                    field.id(),
                    field.label(),
                    field.dataType().name(),
                    (field.stereotype() == null ? DEFAULT_STEREOTYPE : field.stereotype()).name(),
                    field.required(),
                    field.description(),
                    order
            ));
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
    @Transactional
    public void deleteAllById(List<String> selectedIds) {
        if (selectedIds.isEmpty()) {
            return;
        }
        // Fields first: nothing cascades here, and orphaned rows would be picked up again by a later
        // form that reuses the id — git import re-creates a form under a fresh UUID on every run.
        fieldEntityRepository.deleteByFormIdIn(selectedIds);
        formEntityRepository.deleteAllById(selectedIds);
    }
}
