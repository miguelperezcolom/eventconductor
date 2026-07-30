package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceDto;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.create.CreateResourceCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.create.CreateResourceUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.update.UpdateResourceCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.update.UpdateResourceUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ResourceViewModel implements Identifiable {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty
    String name;
    @ReadOnly
    int statusCode;
    @ReadOnly
    long size;
    @ReadOnly
    long milliseconds;
    @ReadOnly
            @Colspan(2)
            @Style("width: 100%;")
    String content;

    final CreateResourceUseCase createResourceUseCase;
    final UpdateResourceUseCase updateResourceUseCase;

    public String create(HttpRequest httpRequest) {
        return createResourceUseCase.handle(new CreateResourceCommand(name));
    }

    public void save(HttpRequest httpRequest) {
        updateResourceUseCase.handle(new UpdateResourceCommand(id, name));
    }

    @Override
    public String id() {
        return id;
    }

    public ResourceViewModel load(ResourceDto resource) {
        id = String.valueOf(resource.id());
        name = resource.name();
        content = resource.content() != null ? (resource.content().length() > 100?resource.content().substring(0, 100) + "...":resource.content()) : "";
        statusCode = resource.statusCode();
        size = resource.size();
        milliseconds = resource.milliseconds();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New resource";
    }
}
