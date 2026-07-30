package io.mateu.workflow.contentservice.infra.in.ui.pages.contenttype;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.contentservice.application.query.dto.ContentTypeDto;
import io.mateu.workflow.contentservice.application.usecases.contenttype.create.CreateContentTypeCommand;
import io.mateu.workflow.contentservice.application.usecases.contenttype.create.CreateContentTypeUseCase;
import io.mateu.workflow.contentservice.application.usecases.contenttype.update.UpdateContentTypeCommand;
import io.mateu.workflow.contentservice.application.usecases.contenttype.update.UpdateContentTypeUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ContentTypeViewModel implements Identifiable {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty String name;

    final CreateContentTypeUseCase createContentTypeUseCase;
    final UpdateContentTypeUseCase updateContentTypeUseCase;

    public String create(HttpRequest httpRequest) {
        return createContentTypeUseCase.handle(new CreateContentTypeCommand(name));
    }

    public void save(HttpRequest httpRequest) {
        updateContentTypeUseCase.handle(new UpdateContentTypeCommand(id, name));
    }

    @Override
    public String id() {
        return id;
    }

    public ContentTypeViewModel load(ContentTypeDto contenttype) {
        id = String.valueOf(contenttype.id());
        name = contenttype.name();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New contenttype";
    }
}
