package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.contentservice.application.query.dto.ContentDto;
import io.mateu.workflow.contentservice.application.usecases.content.create.CreateContentCommand;
import io.mateu.workflow.contentservice.application.usecases.content.create.CreateContentUseCase;
import io.mateu.workflow.contentservice.application.usecases.content.update.UpdateContentCommand;
import io.mateu.workflow.contentservice.application.usecases.content.update.UpdateContentUseCase;
import io.mateu.workflow.contentservice.domain.aggregates.content.Content;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ContentViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreateContentUseCase createContentUseCase;
        final UpdateContentUseCase updateContentUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createContentUseCase.handle(new CreateContentCommand(name));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateContentUseCase.handle(new UpdateContentCommand(id, name));
        }

        @Override
        public String id() {
        return id;
        }

        public ContentViewModel load(ContentDto content) {
        id = String.valueOf(content.id());
        name = content.name();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New content";
        }
        }
