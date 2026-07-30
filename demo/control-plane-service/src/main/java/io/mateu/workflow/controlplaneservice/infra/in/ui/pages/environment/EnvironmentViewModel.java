package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentDto;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.create.CreateEnvironmentCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.create.CreateEnvironmentUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.update.UpdateEnvironmentCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.update.UpdateEnvironmentUseCase;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class EnvironmentViewModel implements Identifiable {
    @EditableOnlyWhenCreating
    String id;
    @NotEmpty
    String name;

    final CreateEnvironmentUseCase createEnvironmentUseCase;
    final UpdateEnvironmentUseCase updateEnvironmentUseCase;

    public String create(HttpRequest httpRequest) {
        return createEnvironmentUseCase.handle(new CreateEnvironmentCommand(id, name));
    }

    public void save(HttpRequest httpRequest) {
        updateEnvironmentUseCase.handle(new UpdateEnvironmentCommand(id, name));
    }

    @Override
    public String id() {
        return id;
    }

    public EnvironmentViewModel load(EnvironmentDto environment) {
        id = String.valueOf(environment.id());
        name = environment.name();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New environment";
    }
}
