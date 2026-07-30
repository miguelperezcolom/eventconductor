package io.mateu.workflow.usersservice.infra.in.ui.pages.permissions;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.usersservice.application.query.dto.PermissionDto;
import io.mateu.workflow.usersservice.application.usecases.permission.create.CreatePermissionCommand;
import io.mateu.workflow.usersservice.application.usecases.permission.create.CreatePermissionUseCase;
import io.mateu.workflow.usersservice.application.usecases.permission.save.SavePermissionCommand;
import io.mateu.workflow.usersservice.application.usecases.permission.save.SavePermissionUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class PermissionViewModel implements Identifiable {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty String name;
    String description;
    String scope;

    final CreatePermissionUseCase createPermissionUseCase;
    final SavePermissionUseCase savePermissionUseCase;

    public String create(HttpRequest httpRequest) {
        return createPermissionUseCase.handle(new CreatePermissionCommand(name, description, scope));
    }

    public void save(HttpRequest httpRequest) {
        savePermissionUseCase.handle(new SavePermissionCommand(id, name, description, scope));
    }

    @Override
    public String id() {
        return id;
    }

    public PermissionViewModel load(PermissionDto permission) {
        id = String.valueOf(permission.id());
        name = permission.name();
        description = permission.description();
        scope = permission.scope();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New permission";
    }
}
