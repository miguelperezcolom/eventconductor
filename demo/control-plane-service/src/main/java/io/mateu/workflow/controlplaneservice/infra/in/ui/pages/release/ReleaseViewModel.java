package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseDto;
import io.mateu.workflow.controlplaneservice.application.usecases.release.create.CreateReleaseCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.create.CreateReleaseUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.release.update.UpdateReleaseCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.update.UpdateReleaseUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ReleaseViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreateReleaseUseCase createReleaseUseCase;
        final UpdateReleaseUseCase updateReleaseUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createReleaseUseCase.handle(new CreateReleaseCommand(name));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateReleaseUseCase.handle(new UpdateReleaseCommand(id, name));
        }

        @Override
        public String id() {
        return id;
        }

        public ReleaseViewModel load(ReleaseDto release) {
        id = String.valueOf(release.id());
        name = release.name();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New release";
        }
        }
