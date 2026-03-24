package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceDto;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.create.CreateResourceCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.create.CreateResourceUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.update.UpdateResourceCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.update.UpdateResourceUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ResourceViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreateResourceUseCase createResourceUseCase;
        final UpdateResourceUseCase updateResourceUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createResourceUseCase.handle(new CreateResourceCommand(name));
        }

        @Override
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
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New resource";
        }
        }
