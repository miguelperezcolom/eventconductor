package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.contentservice.application.query.dto.ContentDto;
import io.mateu.workflow.contentservice.application.usecases.content.ContentValueDto;
import io.mateu.workflow.contentservice.application.usecases.content.create.CreateContentCommand;
import io.mateu.workflow.contentservice.application.usecases.content.create.CreateContentUseCase;
import io.mateu.workflow.contentservice.application.usecases.content.update.UpdateContentCommand;
import io.mateu.workflow.contentservice.application.usecases.content.update.UpdateContentUseCase;
import io.mateu.workflow.contentservice.domain.aggregates.content.Content;
import io.mateu.workflow.contentservice.infra.in.ui.suppliers.ContentTypeIdLabelSupplier;
import io.mateu.workflow.contentservice.infra.in.ui.suppliers.ContentTypeIdOptionsSupplier;
import io.mateu.workflow.contentservice.infra.in.ui.suppliers.LabelIdLabelSupplier;
import io.mateu.workflow.contentservice.infra.in.ui.suppliers.LabelIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ContentViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;
        @Lookup(search = ContentTypeIdOptionsSupplier.class, label = ContentTypeIdLabelSupplier.class)
        String contentType;
        @Lookup(search = LabelIdOptionsSupplier.class, label = LabelIdLabelSupplier.class)
        List<String> labels;
                @MasterDetail(minHeightWhenDetailVisible = "26rem;")
                @Colspan(2)
        List<ContentValueViewModel> values;

        final CreateContentUseCase createContentUseCase;
        final UpdateContentUseCase updateContentUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createContentUseCase.handle(new CreateContentCommand(name, contentType, labels, values.stream()
                .map(value -> new ContentValueDto(value.country(), value.language(), value.value()))
                .toList()));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateContentUseCase.handle(new UpdateContentCommand(id, name, contentType, labels, values.stream()
                .map(value -> new ContentValueDto(value.country(), value.language(), value.value()))
                .toList()));
        }

        @Override
        public String id() {
        return id;
        }

        public ContentViewModel load(ContentDto content) {
        id = String.valueOf(content.id());
        name = content.name();
        contentType = content.contentType();
        labels = content.labels();
        values = content.values().stream()
                .map(value -> new ContentValueViewModel(
                        value.country(),
                        value.language(),
                        value.value()
                        )
                ).toList();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New content";
        }
        }
